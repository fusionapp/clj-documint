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
  (let [config (load-config)
        keystore (open-keystore (get-in config [:keystore :path])
                                (get-in config [:keystore :password]))]
    (component/system-map
     :keystore        keystore
     :session-factory (temp-file-session-factory)
     :renderer        (saucer/renderer (:renderer config {}))
     :signer          (component/using
                       (signer-component
                        (get-in config [:signing :certificate-passwords] {}))
                       [:keystore])
     :app             (component/using
                       (web/new-app)
                       [:session-factory])
     :web-options     (web/jetty-options keystore config)
     :web             (component/using
                       (new-web-server 0)
                       {:handler :app
                        :options :web-options}))))


(def prod-system dev-system)
