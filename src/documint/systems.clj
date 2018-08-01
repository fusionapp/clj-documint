(ns documint.systems
  "Documint system definitions."
  (:require [com.stuartsierra.component :as component]
            [system.core :refer [defsystem]]
            (system.components
             [jetty :refer [new-web-server]])
            [documint.web :as web]
            [documint.render.flying-saucer :as saucer]
            [documint.render.fop :as fop]
            [documint.session :refer [temp-file-session-factory]]
            [documint.config :refer [load-config]]
            [documint.pdf.signing :refer [signer-component]]
            [documint.util :refer [open-keystore]]))


(defn dev-system
  []
  (let [config     (load-config)
        conf       (partial get-in config)
        keystore   (open-keystore (conf [:keystore :path])
                                  (conf [:keystore :password]))
        truststore (when (conf [:truststore])
                     (open-keystore (conf [:truststore :path])
                                    (conf [:truststore :password])))]
    ;; Due to the change of the java color management module towards
    ;; “LittleCMS”, users can experience slow performance in color operations. A
    ;; solution is to disable LittleCMS in favor of the old KCMS (Kodak Color
    ;; Management System).
    ;; https://bugs.openjdk.java.net/browse/JDK-8041125
    (System/setProperty "sun.java2d.cmm" "sun.java2d.cmm.kcms.KcmsServiceProvider")
    ;; https://pdfbox.apache.org/2.0/getting-started.html#rendering-performance
    (System/setProperty "org.apache.pdfbox.rendering.UsePureJavaCMYKConversion" "true")
    (component/system-map
     :keystore               keystore
     :session-factory        (temp-file-session-factory)
     :renderer/flying-saucer (saucer/renderer (conf [:renderer] {}))
     :renderer/fop           (fop/renderer (conf [:renderer-fop] {}))
     :signer                 (component/using
                              (signer-component
                               (conf [:signing :certificate-passwords] {}))
                              [:keystore])
     :app                    (component/using
                              (web/new-app)
                              [:session-factory])
     :web-options            (web/jetty-options keystore truststore config)
     :web                    (component/using
                              (new-web-server 0)
                              {:handler :app
                               :options :web-options}))))


(def prod-system dev-system)
