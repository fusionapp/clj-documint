(ns documint.pdf.core
  "Documint PDF core functions."
  (:import [org.apache.pdfbox.pdmodel PDPage PDResources]
           [org.apache.pdfbox.pdmodel.graphics.form PDFormXObject]
           [org.apache.pdfbox.pdmodel.graphics.image PDImageXObject]))


(defn x-object-length
  "Determine the length in bytes of a `PDXObject`."
  [^PDFormXObject x-obj]
  (.. x-obj getStream getLength))


(defn x-image-dpi
  "Calculate the DPI of an image on a page."
  ([page ^PDImageXObject x-img]
   (x-image-dpi page x-img (.getWidth x-img) (.getHeight x-img)))

  ([^PDPage page ^PDImageXObject x-img w h]
   (let [crop-box (.getCropBox page)
         dpi      72
         x-factor (/ (.getWidth crop-box) dpi)
         y-factor (/ (.getHeight crop-box) dpi)]
     {:x        (int (/ w x-factor))
      :y        (int (/ h y-factor))})))


(defn x-image-info
  "X-Image information."
  [page ^PDImageXObject x-img]
  (let [w          (.getWidth x-img)
        h          (.getHeight x-img)
        components (.. x-img getColorSpace getNumberOfComponents)]
    {:width          w
     :height         h
     :dpi            (x-image-dpi page x-img w h)
     :colorspace     (.. x-img getColorSpace getName)
     :components     components
     :encoded-length (x-object-length x-img)
     :suffix         (.getSuffix x-img)
     :interpolate    (.getInterpolate x-img)
     :bpp            (* components (.getBitsPerComponent x-img))}))


(defn x-object-info
  "Obtain a `hash-map` of detailed information specific to `x-obj`."
  [page x-obj]
  (condp instance? x-obj
    PDImageXObject (x-image-info page x-obj)
    {}))


(defn x-objects
  "Return a `seq` of all X-Objects within a set of PDF resources.

  `PDFormXObject` resources will be recursed into."
  ([^PDPage page ^PDResources resources pred]
   (mapcat
    (fn [n]
      (let [x-obj (.getXObject resources n)]
        (apply conj
               [{:name      n
                 :x-object  x-obj
                 :page      page
                 :container resources
                 :length    (x-object-length x-obj)
                 :info      (x-object-info page x-obj)}]
               (when (instance? PDFormXObject x-obj)
                 (x-objects page (.getResources x-obj) pred)))))
    (.getXObjectNames resources))))


(defn x-image?
  "Is this an X-Image?"
  [x-obj]
  (instance? PDImageXObject x-obj))


(defn page-images
  "Return a `seq` of all X-Objects that are images within a set of PDF resources."
  [^PDPage page]
  (x-objects page (.getResources page) x-image?))
