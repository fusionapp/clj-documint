(ns documint.render
  "Render documents.")


(defprotocol IRenderer
  "Document renderer."
  (render [this ^java.io.InputStream input ^java.io.OutputStream output options]
    "Render an input stream into an output stream."))
