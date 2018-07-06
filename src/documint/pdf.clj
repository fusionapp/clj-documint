(ns documint.pdf
  "Portable Document Format utilities."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as jio]
            [clojure.java.shell]
            [clojure.tools.logging :as log]
            [documint.pdf.crush :as crush]
            [documint.pdf.signing :as signing]
            [documint.pdf.stamp :as stamp]
            [documint.render :refer [render]]
            [documint.util :refer [wait-close time-body-ms]]
            [com.climate.claypoole :as cp])
  (:import [java.io InputStream OutputStream]
           [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.multipdf PDFMergerUtility]))

(set! *warn-on-reflection* true)


(defn- ^PDDocument content->doc
  ""
  [content]
  (let [^InputStream stream (:stream content)]
    (PDDocument/load stream)))


(defn render-html
  "Render a PDF document from an HTML source document."
  [renderer options content]
  (log/info "Rendering a document"
            {:renderer renderer
             :options options})
  (fn [^OutputStream output]
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
    (fn [^OutputStream output]
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
                                (str "pdf:-[" page-index "]")
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
        write-thumb (fn [page-index ^OutputStream output]
                      (page-thumbnail (.toByteArray baos)
                                      page-index
                                      dpi
                                      output)
                      (done-one page-index)
                      "image/jpeg")]
    (map #(partial write-thumb %) pages)))


(defn- translate-page-name
  "Translate a page name (possibly an index) into a page index."
  [^PDDocument doc page-name]
  (if (number? page-name)
    page-name
    (condp = page-name
      "first" 1
      "last" (.getNumberOfPages doc))))


(defn- expand-page-name
  "Expand a page name into a vector of page indices."
  [^PDDocument doc name]
  (let [xlate (partial translate-page-name doc)]
    (if (vector? name)
      (let [[a b] (map xlate name)]
        (vec
         (if (> a b)
           (range a (dec b) -1)
           (range a (inc b)))))
      [(xlate name)])))


(defn- page-extractor
  "Extract specific pages from a PDF source document into a new PDF document.

  `page-indices` is a vector of page names, only those pages from the original
  document will be contained in the resulting document, in the order they are
  specified.

  A page name may be an index (integer), a name (string) such as 'first', 'last',
  etc., or a vector of exactly two elements of the previous types. In the case
  of a vector the first element is the start of the span, the second is the end
  of the span. Spans may run backwards. "
  [^PDDocument src-doc page-indices]
  (let [dst-doc (PDDocument.)]
    (doto dst-doc
      ;; We forego copying `PDViewerPreferences` since we're tearing pages out,
      ;; whatever viewer preferences were relevant for the whole document are
      ;; unlikely to be relevant now.
      (.setDocumentInformation (.getDocumentInformation src-doc)))
    (doseq [page-index (mapcat #(expand-page-name src-doc %) page-indices)]
      (let [^PDPage page (.getPage src-doc (dec page-index))
            imported     (.importPage dst-doc page)]
        ;; Media information is copied by `PDPage.importPage`:
        ;; https://github.com/apache/pdfbox/blob/2.0.11/pdfbox/src/main/java/org/apache/pdfbox/pdmodel/PDDocument.java#L678
        (doto imported
          (.setResources (.getResources page)))))
    dst-doc))


(defn split
  "Split a document into several new documents.

  `page-groups` is a vector of vectors of page numbers, each top-level vector
  represents a document containing only those pages from the original document,
  in the order they are specified."
  [page-groups content]
  (log/info "Splitting document"
            {:page-groups page-groups})
  (let [src-doc   (content->doc content)
        docs      (map (partial page-extractor src-doc) page-groups)
        done-one  (wait-close src-doc docs)
        write-doc (fn [^PDDocument doc ^OutputStream output]
                    (.save doc output)
                    (.close doc)
                    (done-one doc)
                    "application/pdf")]
    (map #(partial write-doc %) docs)))


(defn metadata
  "Retrieve a map of PDF metadata."
  [content]
  (log/info "Obtaining document metadata")
  (with-open [doc (content->doc content)]
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
  (let [sign-doc (fn [content ^OutputStream output]
                   (with-open [doc (content->doc content)]
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
  (fn [^OutputStream output]
    (log/info "Crushing document pages")
    (with-open [src-doc (content->doc content)]
      (.save (crush/crush-document! src-doc compression-profile) output))
    "application/pdf"))


(defn stamp
  "Stamp documents with a watermark document."
  [watermark contents]
  (log/info "Stamping documents")
  (letfn [(watermark-doc [content ^OutputStream output]
            (with-open [src-doc (doto (content->doc content)
                                  ;; Strip security from the source so we can
                                  ;; modify the document.
                                  (.setAllSecurityToBeRemoved true))
                        stamp-doc (content->doc watermark)]
              (.save (stamp/stamp stamp-doc src-doc) output))
            "application/pdf")]
    (map #(partial watermark-doc %) contents)))
