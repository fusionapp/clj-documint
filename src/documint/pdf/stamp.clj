(ns documint.pdf.stamp
  "PDF stamping."
  (:import [org.apache.pdfbox.pdmodel
            PDDocument
            PDPage
            PDPageContentStream
            PDPageContentStream$AppendMode]
           [org.apache.pdfbox.pdmodel.graphics.state PDExtendedGraphicsState]
           [org.apache.pdfbox.pdmodel.graphics.form PDFormXObject]
           [org.apache.pdfbox.pdmodel.graphics.optionalcontent
            PDOptionalContentProperties
            PDOptionalContentGroup]
           [org.apache.pdfbox.cos COSName]
           [org.apache.pdfbox.multipdf LayerUtility]
           [org.apache.pdfbox.util Matrix]
           [java.awt.geom AffineTransform]))


(defn- page-cropbox [page]
  (let [box (.getCropBox page)]
    [(.getWidth box) (.getHeight box)]))


(defn- portrait-orientation [[w h]]
  (if (> w h)
    [h w]
    [w h]))


(defn- landscape? [page]
  (let [[w h] (page-cropbox page)]
    (> w h)))


(defn- transformation [align src dst]
  (let [transform (AffineTransform.)
        [dw dh]   (portrait-orientation (page-cropbox dst))
        [sw sh]   (portrait-orientation (page-cropbox src))]
    (if (landscape? dst)
      (case align
        :bottom-right (doto transform
                        (.rotate (Math/toRadians 90))
                        (.translate (- dw sw) (- (+ sh (- dh sh))))))
      (case align
        :bottom-right (doto transform
                        (.translate (- dw sw) (- dh sh)))))
    transform))


(defn- append-form-as-layer
  "Add a `PDFormXObject` to a page, with a transformation, in an Optional
  Content Group."
  [^PDDocument document
   ^PDFormXObject xobj
   ^PDPage page
   ^AffineTransform transform
   ^String layer-name]
  (let [ocprops (or (.. document (getDocumentCatalog) (getOCProperties))
                    (PDOptionalContentProperties.))
        layer   (or (.getGroup ocprops layer-name)
                    (PDOptionalContentGroup. layer-name))]
    (when-not (.hasGroup ocprops layer-name)
      (.addGroup ocprops layer))

    (with-open [stream (PDPageContentStream. document
                                             page
                                             PDPageContentStream$AppendMode/APPEND
                                             true)]
      (doto stream
        (.beginMarkedContent COSName/OC layer)
        (.saveGraphicsState)
        (.transform (Matrix. transform))
        (.setGraphicsStateParameters (doto (PDExtendedGraphicsState.)
                                       (.setAlphaSourceFlag true)
                                       (.setNonStrokingAlphaConstant (float 0.75))
                                       (.setStrokingAlphaConstant (float 0.75))))
        (.drawForm xobj)
        (.restoreGraphicsState)
        (.endMarkedContent))))
  document)


(defn ^PDDocument stamp
  "Stamp all the pages of a document with a watermark document."
  [^PDDocument watermark-document ^PDDocument document]
  (let [src-page (.getPage watermark-document 0)
        lu       (LayerUtility. document)
        xobj     (.importPageAsForm lu watermark-document src-page)]
    (doseq [dst-page (.getPages document)]
      (.wrapInSaveRestore lu dst-page)
      (append-form-as-layer document
                            xobj
                            dst-page
                            (transformation :bottom-right src-page dst-page)
                            "fusion-stamp")))
  document)
