(ns documint.render.fop
  "Apache FOP `IRenderer` implementation.

  See <https://xmlgraphics.apache.org/fop>."
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as jio]
            [documint.render :refer [IRenderer]])
  (:import [java.io InputStream OutputStream ByteArrayOutputStream]
           [java.net URI]
           [java.util.logging Logger Level]
           [org.apache.commons.logging LogFactory]
           [be.re.css CSSToXSLFO]
           [org.apache.fop.apps FopFactory MimeConstants]
           [javax.xml.transform TransformerFactory]
           [javax.xml.transform.stream StreamSource]
           [javax.xml.transform.sax SAXResult]))


(def ^:private default-base-uri (.toURI (jio/file ".")))


(defrecord ApacheFOPRenderer [^FopFactory fop-factory]
  IRenderer
  (render [this input output
           {:keys [base-uri] :as options}]
    (let [^InputStream input   input
          ^OutputStream output output
          output-fo            (ByteArrayOutputStream.)
          ^URI base-uri        (if (clojure.string/blank? base-uri)
                                 default-base-uri
                                 (URI. base-uri))]
      (log/info "Transforming HTML to XSL-FO with css2xslfo"
                {:base-uri base-uri})
      (log/spy
       (CSSToXSLFO/convert
        input
        output-fo
        (.toURL base-uri)
        nil
        (.getResource CSSToXSLFO "/catalog")
        {"base-url" (.toString base-uri)}
        nil
        false
        false))
      (log/info "Rendering a document with Apache FOP"
                {:base-uri base-uri})
      (let [fop         (.newFop fop-factory MimeConstants/MIME_PDF output)
            transformer (.newTransformer (TransformerFactory/newInstance))
            src         (StreamSource. (jio/input-stream (.toByteArray output-fo)))]
        (log/spy
         (.transform transformer src (SAXResult. (.getDefaultHandler fop))))))))


(defn- set-logging
  "Enabled / disable Apache FOP logging."
  [enabled?]
  ;; For reasons I can't understand about Java logging, unless we call this
  ;; Apache Commons Logging function first, setting the level on the JDK logger
  ;; has no effect.
  (LogFactory/getLog "org.apache.fop.apps.FOUserAgent")
  (.. (Logger/getLogger "org.apache.fop.apps.FOUserAgent")
      (setLevel (if enabled? Level/INFO Level/OFF))))


(defn renderer
  "Construct an Apache FOP `IRenderer` implementation.

  Recognised options:

  `xconf`: Path string to an Apache FOP config file."
  [{:keys [xconf
           logging?]
    :as options}]
  (log/info "Initialising Apache FOP renderer"
            {:options options})
  (set-logging logging?)
  (let [fop-factory (FopFactory/newInstance (if xconf
                                              (jio/file xconf)
                                              default-base-uri))]
    (map->ApacheFOPRenderer {:fop-factory fop-factory})))
