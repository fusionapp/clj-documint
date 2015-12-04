(ns documint.pdf
  "Portable Document Format utilities."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as jio]
            [clojure.tools.logging :as log]
            [ring.util.io :refer [piped-input-stream]]
            [documint.util :refer [wait-close]])
  (:import [java.io InputStream]
           [java.awt Color]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.rendering PDFRenderer]
           [org.apache.pdfbox.multipdf PDFMergerUtility]))


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
    (piped-input-stream
     (fn [output]
       (doto merger
         (.setDestinationStream output)
         (.mergeDocuments nil))))))


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
        {:keys [w h
                nw nh
                sx sy]} (thumbnail-size dimensions breadth)
        image           (BufferedImage. nw nh BufferedImage/TYPE_INT_RGB)
        g               (.createGraphics image)]
    (doto g
      (.setBackground Color/WHITE)
      (.clearRect 0 0 w h)
      (.scale sx sy))
    ; If the page is landscape, rotate it back.
    (when (contains? #{90 270} rotation)
      (doto g
        (.rotate (Math/toRadians (- 360 rotation)))
        (.translate 0 (int (- w)))))
    (.renderPageToGraphics renderer page-index g)
    image))


(defn thumbnails
  "Create thumbnail images for each page in `content`.

  `breadth` is the widest part of the thumbnail in pixels, the shorter end will
  be scaled accordingly."
  [breadth format-name content]
  (log/info "Creating thumbnail images")
  (let [doc         (PDDocument/load (:stream content))
        renderer    (PDFRenderer. doc)
        images      (map (partial page-thumbnail doc renderer breadth)
                         (range (.getNumberOfPages doc)))
        done-one    (wait-close doc images)
        write-image (fn [image]
                      (piped-input-stream
                       (fn [output]
                         (ImageIO/write image format-name output)
                         (done-one image))))]
    (map write-image images)))


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
        write-doc (fn [doc]
                    (piped-input-stream
                     (fn [output]
                       (.save doc output)
                       (.close doc)
                       (done-one doc))))]
    (map write-doc docs)))


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


(defn shrink
  ""
  [content quality]
  ; https://github.com/bnanes/shrink-pdf/blob/master/src/main/java/edu/emory/cellbio/ShrinkPDF.java
  )
