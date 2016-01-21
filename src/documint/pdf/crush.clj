(ns documint.pdf.crush
  "PDF document crushing."
  (:require [clojure.tools.logging :as log]
            [ring.util.io :refer [piped-input-stream]]
            [documint.pdf.core :refer [page-images x-object-length]])
  (:import [java.awt RenderingHints]
           [java.awt.image BufferedImage]
           [javax.imageio
            ImageIO ImageWriteParam IIOImage]
           [org.apache.pdfbox.cos COSName]
           [org.apache.pdfbox.io RandomAccessBuffer]
           [org.apache.pdfbox.pdmodel.graphics.form PDFormXObject]
           [org.apache.pdfbox.pdmodel.graphics.image
            PDImageXObject JPEGFactory CCITTFactory]
           [com.twelvemonkeys.imageio.metadata.jpeg JPEGQuality]))


(defn- writer-for-format
  "Obtain the first `ImageWriter` for a given format name."
  [format-name]
  (-> format-name
      ImageIO/getImageWritersByFormatName
      iterator-seq
      first))


(defn- jpeg?
  "Is this `PDImageXObject` a JPEG?"
  [^PDImageXObject x-img]
  (= "jpg" (-> x-img
               .getSuffix
               clojure.string/lower-case)))


(defn- jpeg-stream
  "Open an `InputStream` for a JPEG `PDImageXObject`."
  [^PDImageXObject x-img]
  (.createInputStream (.getStream x-img)
                      [(.getName COSName/DCT_DECODE)
                       (.getName COSName/DCT_DECODE_ABBREVIATION)]))


(defn- estimate-image-quality
  "Estimate the image quality of a `PDImageXObject`.

  Returns a value between 0 and 1."
  [^PDImageXObject x-img]
  (let [dp     (format "dump-%s" (swap! counter inc))]
    (with-open [stream (jpeg-stream x-img)]
      (cond
        (jpeg? x-img) (JPEGQuality/getJPEGQuality (ImageIO/createImageInputStream stream))
        :else         1.0))))


(defn- bilevel-image
  "Convert a `BufferedImage` to a bilevel (1-bit) `BufferedImage`.

  The conversion is naive and doesn't allow for any kind of thresholding."
  [^BufferedImage src]
  (let [dst (BufferedImage. (.getWidth src)
                            (.getHeight src)
                            BufferedImage/TYPE_BYTE_BINARY)]
    (doto (.getGraphics dst)
      (.drawImage src 0 0 nil)
      (.dispose))
    dst))


(defn- greyscale-image
  "Convert a `BufferedImage` to a grayscale (8-bit) `BufferedImage`."
  [^BufferedImage src]
  (let [dst (BufferedImage. (.getWidth src)
                            (.getHeight src)
                            BufferedImage/TYPE_BYTE_GRAY)]
    (doto (.getGraphics dst)
      (.drawImage src 0 0 nil)
      (.dispose))
    dst))


(defn- downsample-image
  "Downsample a `BufferedImage` to a given DPI.

  No upsampling takes place and no downsampling takes place if the source image
  does not have at least twice the target DPI."
  [^BufferedImage src src-dpi target-dpi]
  (let [xf (/ (:x src-dpi) (:x target-dpi))
        yf (/ (:y src-dpi) (:y target-dpi))]
    (if (or (>= xf 2) (>= yf 2))
      (let [w   (.getWidth src)
            h   (.getHeight src)
            sw  (int (/ w xf))
            sh  (int (/ h yf))
            dst (BufferedImage. sw sh (.getType src))]
        (doto (.getGraphics dst)
          (.setRenderingHint RenderingHints/KEY_INTERPOLATION
                             RenderingHints/VALUE_INTERPOLATION_BILINEAR)
          (.drawImage src 0 0 sw sh 0 0 w h nil)
          (.dispose))
        dst)
      src)))


(defprotocol IImageCompressor
  "`PDImageXObject` compressor."
  (transform-image [this object-info ^PDImageXObject x-img]
    "Transform a `PDImageXObject` into a `BufferedImage`.")

  (uncompressable? [this object-info ^PDImageXObject x-img]
    "Can the image be compressed?

    Returns a `hash-map` containing a reason (and extended information) that it
    cannot be compressed, or `nil` if it can be compressed.")

  (compress ^PDImageXObject [this doc object-info ^PDImageXObject x-img]
    "Compress an image."))


(defn write-image
  "Produce an `InputStream` containing `BufferedImage` image data written using
  an `ImageWriter`."
  [writer write-param img]
  (piped-input-stream
   (fn [output]
     (doto writer
       (.setOutput (ImageIO/createImageOutputStream output))
       (.write nil (IIOImage. img nil nil) write-param)))))


(defrecord CCITTCompressor [writer write-param dpi]
  IImageCompressor
  (transform-image [this object-info x-img]
    (-> (.getImage x-img)
        (downsample-image (:dpi object-info) dpi)
        bilevel-image))

  (uncompressable? [this object-info x-img]
    (let [bpp (:bpp object-info)]
      (when (<= bpp 1)
        {:message "Already a bilevel image"})))

  (compress [this doc object-info x-img]
    (if-let [reason (uncompressable? this object-info x-img)]
      (do
        (log/info "X-Object image is not compressable"
                  {:compressor this
                   :reason     reason})
        x-img)
      (CCITTFactory/createFromRandomAccess
       doc
       (RandomAccessBuffer.
        (write-image writer
                     write-param
                     (transform-image this object-info x-img)))))))


(defn- ccitt-compressor
  "Create an `IImageCompressor` instance that compresses images to CCITT bi-level
  (1-bit) images."
  [{:keys [dpi]}]
  (let [writer (writer-for-format "tiff")]
    (map->CCITTCompressor
     {:writer      writer
      :write-param (doto (.getDefaultWriteParam writer)
                     (.setCompressionMode ImageWriteParam/MODE_EXPLICIT)
                     (.setCompressionType "CCITT T.6"))
      :dpi         {:x dpi
                    :y dpi}})))


(defrecord JPEGCompressor [writer write-param dpi bpp quality]
  IImageCompressor
  (transform-image [this object-info x-img]
    (-> (.getImage x-img)
        (downsample-image (:dpi object-info) dpi)
        (cond->
            (= bpp 8) greyscale-image)))

  (uncompressable? [this object-info x-img]
    (let [b (:bpp object-info)
          q (estimate-image-quality x-img)]
      (cond
        (< b bpp)     {:message (format "%s-bit is lower quality than %s-bit" b bpp)}
        (< q quality) {:message (format "Quality %s is more compressed than %s" q quality)})))

  (compress [this doc object-info x-img]
    (if-let [reason (uncompressable? this object-info x-img)]
      (do
        (log/info "X-Object image is not compressable"
                  {:compressor this
                   :reason     reason})
        x-img)
      (JPEGFactory/createFromStream
       doc
       (write-image writer
                    write-param
                    (transform-image this object-info x-img))))))


(defn- jpeg-compressor
  "Create an `IImageCompressor` instance that uses JPEG compression, at `bpp`
  bits-per-pixel and `quality` (a value between 0 and 1) compression quality."
  [{:keys [bpp quality dpi]}]
  (let [writer      (writer-for-format "jpeg")]
    (map->JPEGCompressor
     {:writer      writer
      :write-param (doto (.getDefaultWriteParam writer)
                     (.setOptimizeHuffmanTables true)
                     (.setCompressionMode ImageWriteParam/MODE_EXPLICIT)
                     (.setCompressionQuality quality))
      :dpi         {:x dpi
                    :y dpi}
      :bpp         bpp
      :quality     quality})))


(def ^:private compression-profiles
  "Compression profiles.

  `:text`: CCITT bi-level compression at 300 DPI
  `:photo-grey`: JPEG 8-bit (per pixel) compression with 45% quality at 150 DPI
  `:photo`: JPEG 24-bit (per pixel) compression with 65% quality at 150 DPI"
  {:text       #(ccitt-compressor {:dpi 300})
   :photo-grey #(jpeg-compressor {:dpi     150
                                  :bpp     8
                                  :quality 0.45})
   :photo      #(jpeg-compressor {:dpi     150
                                  :bpp     24
                                  :quality 0.65})})


(defn- page->map
  "Build a `hash-map` that represents pertinent information in a `PDPage`.

  `:page`: `PDPage`
  `:length`: Total size in bytes of all contained images
  `:x-objects`: `seq` of all the images contained in the page"
  [page]
  (let [x-objects (sort-by :length (page-images page))]
    {:page      page
     :length    (reduce + (map :length x-objects))
     :x-objects x-objects}))


(defn- compress-image
  "Compress a single `PDImageXObject` in a lossy fashion."
  [document compressor object-info x-img]
  (let [new-img (compress compressor document object-info x-img)
        shrunk  (max 0 (- (x-object-length x-img)
                          (x-object-length new-img)))]
    (when-not (zero? shrunk)
      [shrunk new-img])))


(defn- compress-page!
  "Compress a document page, in a lossy fashion, with the given
  `IImageCompressor`.

  The `PDPage`'s image resources are mutated by replacing original images with
  compressed images; unless the compressed image is bigger than the original in
  which case no action is taken."
  [document compressor page]
  (for [{:keys [name x-object info container]} (:x-objects page)
        :let [shrunk 0]]
    (if-let [[shrunk x-img] (compress-image document compressor info x-object)]
      (do (.put container name x-img)
          shrunk)
      shrunk)))


(defn crush-document!
  "Compress a document, in a lossy fashion, according to a compression profile.

  Pages in the document are mutated, see `compress-page!` for more information.

  See `compression-profiles` for valid profiles."
  [document compression-profile]
  (let [compressor ((compression-profiles compression-profile))
        pages      (map page->map (.getPages document))
        shrunk     (transduce cat +
                              (map #(compress-page! document compressor %)
                                   pages))]
    document))
