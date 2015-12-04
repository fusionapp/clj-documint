(ns documint.render
  "Render documents."
  (:require [clojure.java.io :as jio]
            [clojure.tools.logging :as log]
            [ring.util.io :refer [piped-input-stream]]))


(defprotocol IRenderer
  "Document renderer."
  (render [this ^InputStream input ^OutputStream output options]
   "Render an input stream into an output stream."))


(defn render-html
  "Render a document from HTML content."
  [renderer options content]
  (log/info "Rendering a document"
            {:options options
             :content content})
  (piped-input-stream
   (fn [output]
     (log/spy
      (render renderer (:stream content) output options)))))
