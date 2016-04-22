(ns documint.systems
  "Documint system definitions."
  (:require [com.stuartsierra.component :as component]
            [system.core :refer [defsystem]]
            (system.components
             [jetty :refer [new-web-server]])
            [documint.web :as web]
            [documint.render.flying-saucer :as saucer]
            [documint.session :refer [temp-file-session-factory]]
            [documint.config :refer [load-config]]
            [documint.pdf.signing :refer [signer-component]]
            [documint.util :refer [open-keystore]]))


(defn dev-system
  []
  (let [config          (load-config)
        conf            (partial get-in config)
        keystore        (open-keystore (conf [:keystore :path])
                                       (conf [:keystore :password]))
        truststore      (when (conf [:truststore])
                          (open-keystore (conf [:truststore :path])
                                         (conf [:truststore :password])))]
    (component/system-map
     :keystore        keystore
     :truststore      truststore
     :session-factory (temp-file-session-factory)
     :renderer        (saucer/renderer (conf [:renderer] {}))
     :signer          (component/using
                       (signer-component (conf [:signing :certificate-passwords] {}))
                       [:keystore])
     :app             (component/using
                       (web/new-app)
                       [:session-factory])
     :web-options     (web/jetty-options keystore truststore config)
     :web             (component/using
                       (new-web-server 0)
                       {:handler :app
                        :options :web-options}))))


(def prod-system dev-system)
