(ns documint.pdf
  "Portable Document Format utilities."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as jio]
            [clojure.tools.logging :as log]
            [documint.pdf.crush :as crush]
            [documint.pdf.signing :as signing]
            [documint.render :refer [render]]
            [documint.util :refer [wait-close ppmap]])
  (:import [java.io InputStream]
           [java.awt Color]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.rendering PDFRenderer]
           [org.apache.pdfbox.multipdf PDFMergerUtility]))


(defn render-html
  "Render a PDF document from an HTML source document."
  [renderer options content]
  (log/info "Rendering a document"
            {:options options})
  (fn [output]
    (log/spy
     (render renderer (:stream content) output options))
    "application/pdf"))


(defn concatenate
  "Concatenate several contents together.

  `contents` is a list of contents, as returned by
  `clojure.session/get-content`, to be concatenated in the order they appear."
  [contents]
  (log/info "Concatenating documents")
  (let [streams (map :stream contents)
        merger  (PDFMergerUtility.)]
    (doseq [^InputStream s streams]
      (.addSource merger s))
    (fn [output]
      (doto merger
        (.setDestinationStream output)
        (.mergeDocuments nil))
      "application/pdf")))


(defn- thumbnail-size
  "Determine the resulting thumbnail size given the page dimensions and desired
  breadth."
  [dimensions breadth]
  (let [w       (.getWidth dimensions)
        h       (.getHeight dimensions)
        ratio   (/ w h)
        [nw nh] (if (> w h)
                  [breadth (int (/ breadth ratio))]
                  [(int (* breadth ratio)) breadth])]
    {:w  w
     :h  h
     :nw nw
     :nh nh
     :sx (float (/ nw w))
     :sy (float (/ nh h))}))


(defn- page-thumbnail
  "Create a `BufferedImage` thumbnail for a specific page index."
  [doc renderer breadth page-index]
  (log/info "Creating a single thumbnail image"
            {:breadth    breadth
             :page-index page-index})
  (let [page            (.getPage doc page-index)
        dimensions      (.getMediaBox page)
        rotation        (.getRotation page)
        {:keys [sx sy]} (thumbnail-size dimensions breadth)]
    (.renderImage renderer page-index (max sx sy))))


(defn thumbnails
  "Create thumbnail images for each page in `content`.

  `breadth` is the widest part of the thumbnail in pixels, the shorter end will
  be scaled accordingly."
  [breadth content]
  (log/info "Creating thumbnail images")
  (let [doc         (PDDocument/load (:stream content))
        renderer    (PDFRenderer. doc)
        images      (ppmap 4
                          (partial page-thumbnail doc renderer breadth)
                          (range (.getNumberOfPages doc)))
        done-one    (wait-close doc images)
        write-image (fn [image output]
                      (ImageIO/write image "jpg" output)
                      (done-one image)
                      "image/jpeg")]
    (map #(partial write-image %) images)))


(defn- page-extractor
  "Split a PDF document into several other documents consisting of specific
  pages.

  `page-indices` is a vector of page numbers, only those pages from the original
  document will be contained in the resulting document, in the order they are
  specified."
  [src-doc page-indices]
  (let [dst-doc (PDDocument.)]
    (doto dst-doc
      (.setDocumentInformation (.getDocumentInformation src-doc))
      (.. getDocumentCatalog
          (setViewerPreferences
           (.. src-doc
               getDocumentCatalog
               getViewerPreferences))))
    (doseq [page-index page-indices]
      (let [page     (.getPage src-doc (dec page-index))
            imported (.importPage dst-doc page)]
        (doto imported
          (.setCropBox (.getCropBox page))
          (.setMediaBox (.getMediaBox page))
          (.setResources (.getResources page))
          (.setRotation (.getRotation page)))))
    dst-doc))


(defn split
  "Split a document into several new documents.

  `page-groups` is a vector of vectors of page numbers, each top-level vector
  represents a document containing only those pages from the original document,
  in the order they are specified."
  [page-groups content]
  (log/info "Splitting document"
            {:page-groups page-groups})
  (let [src-doc   (PDDocument/load (:stream content))
        docs      (map (partial page-extractor src-doc) page-groups)
        done-one  (wait-close src-doc docs)
        write-doc (fn [doc output]
                    (.save doc output)
                    (.close doc)
                    (done-one doc)
                    "application/pdf")]
    (map #(partial write-doc %) docs)))


(defn metadata
  "Retrieve a map of PDF metadata."
  [content]
  (log/info "Obtaining document metadata")
  (with-open [doc (PDDocument/load (:stream content))]
    (let [info (.getDocumentInformation doc)]
      {:page-count (.getNumberOfPages doc)
       :version    (.getVersion doc)
       :title      (.getTitle info)
       :subject    (.getSubject info)
       :author     (.getAuthor info)
       :creator    (.getCreator info)
       :producer   (.getProducer info)})))


(defn sign
  ""
  [signer certificate-alias location reason contents]
  (log/info "Signing documents"
            {:certificate-alias certificate-alias})
  (let [sign-doc (fn [content output]
                   (with-open [doc (PDDocument/load (:stream content))]
                     (signing/sign-document signer
                                            doc
                                            certificate-alias
                                            location
                                            reason
                                            output))
                   "application/pdf")]
    (map #(partial sign-doc %) contents)))


(defn crush
  ""
  [compression-profile content]
  (log/info "Preparing document crush"
            {:compression-profile compression-profile})
  (fn [output]
    (log/info "Crushing document pages")
    (with-open [src-doc (PDDocument/load (:stream content))]
      (.save (crush/crush-document! src-doc compression-profile) output))
    "application/pdf"))
