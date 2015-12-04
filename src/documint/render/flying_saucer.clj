(ns documint.render.flying-saucer
  "Flying Saucer `IRenderer` implementation.

  See <https://github.com/flyingsaucerproject/flyingsaucer>."
  (:require [clojure.tools.logging :as log]
            [documint.render :refer [IRenderer]])
  (:import [org.xhtmlrenderer.pdf ITextRenderer]
           [org.xhtmlrenderer.resource XMLResource]))


(defrecord FlyingSaucerRenderer [renderer]
  IRenderer
  (render [this input output {:keys [base-uri]
                              :as options}]
    (log/info "Rendering a document with Flying Saucer")
    (log/spy
     (doto renderer
       (.setDocument
        (.getDocument (XMLResource/load input)) base-uri)
       (.layout)
       (.createPDF output)))))


(defn renderer
  "Construct a Flying Saucer `IRenderer` implementation.

  Recognised options:

  `font-path`: Path string to additional font directories."
  [{:keys [font-path]
    :as options}]
  (let [renderer (ITextRenderer.)]
    (when font-path
      (.addFontDirectory (.getFontResolver renderer) font-path true))
    (.. renderer
        (getSharedContext)
        (getTextRenderer)
        (setSmoothingThreshold -1))
    (map->FlyingSaucerRenderer {:renderer renderer})))
