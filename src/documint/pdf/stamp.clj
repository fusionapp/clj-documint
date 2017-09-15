(ns documint.pdf.stamp
  "PDF stamping."
  (:import [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.multipdf Overlay Overlay$Position]))


(defn ^PDDocument stamp
  "Stamp the first page of a document with a watermark document."
  [^PDDocument watermark-document ^PDDocument document]
  (let [overlay (doto (Overlay.)
                  (.setInputPDF document)
                  (.setOverlayPosition Overlay$Position/FOREGROUND)
                  (.setFirstPageOverlayPDF watermark-document))]
    (.overlay overlay {})))

