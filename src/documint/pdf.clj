(ns documint.pdf
  "Portable Document Format utilities."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as jio]
            [clojure.tools.logging :as log]
            [documint.pdf.crush :as crush]
            [documint.pdf.signing :as signing]
            [documint.render :refer [render]]
            [documint.util :refer [wait-close time-body-ms]]
            [com.climate.claypoole :as cp])
  (:import [java.io InputStream]
           [org.apache.pdfbox.pdmodel PDDocument]
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


(defn- page-thumbnail
  "Generate a page thumbnail and write it to an `OutputStream`."
  [input page-index dpi output]
  (log/info "Generating page thumbnail"
            {:page-index page-index
             :dpi        dpi})
  (let [[ms {:keys [exit err out]}]
        (time-body-ms
         (clojure.java.shell/sh "convert"
                                "-density" (str dpi)
                                (str "-[" page-index "]")
                                "jpeg:-"
                                :in input
                                :out-enc :bytes))]
    (when-not (zero? exit)
      (throw (ex-info "Thumbnail external process error"
                      {:causes [[:external-process-error
                                 {:exit-code exit
                                  :stderr    err}]]})))
    (log/info "Done generating thumbnail"
              {:page-index page-index
               :ms         ms})
    (clojure.java.io/copy out output)))


(defn thumbnails
  "Create thumbnail images for each page in `content`.

  `dpi` is pixel density of the resulting thumbnail."
  [dpi content]
  (log/info "Creating thumbnail images")
  (let [baos        (java.io.ByteArrayOutputStream.)
        _           (-> content
                        :stream
                        (clojure.java.io/copy baos))
        doc         (PDDocument/load (.toByteArray baos))
        pages       (range (.getNumberOfPages doc))
        done-one    (wait-close doc pages)
        write-thumb (fn [page-index output]
                      (page-thumbnail (.toByteArray baos)
                                      page-index
                                      dpi
                                      output)
                      (done-one page-index)
                      "image/jpeg")]
    (map #(partial write-thumb %) pages)))


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
