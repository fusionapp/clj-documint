(ns documint.systems
  "Documint system definitions."
  (:require [com.stuartsierra.component :as component]
            [system.core :refer [defsystem]]
            (system.components
             [jetty :refer [new-web-server]])
            [environ.core :refer [env]]
            [documint.web :as web]
            [documint.render.flying-saucer :as saucer]
            [documint.session :refer [temp-file-session-factory]]
            [documint.actions.pdf :as pdf-actions]
            [documint.config :refer [load-config]]
            [documint.pdf.signing :as signing]
            [documint.util :refer [open-keystore]]))


(defn dev-system
  ""
  []
  (let [config (load-config)]
    (component/system-map
     :keystore        (open-keystore (get-in config [:keystore :path])
                                     (get-in config [:keystore :password]))
     :session-factory (temp-file-session-factory)
     :renderer        (saucer/renderer (:renderer config {}))
     :signer          (component/using
                       (signing/signer-component
                        (get-in config [:signing :certificate-passwords] {}))
                       [:keystore])
     :app             (component/using
                       (web/new-app)
                       [:session-factory])
     :web             (component/using
                       (new-web-server (Integer. (env :documint-port)))
                       {:handler :app})
     )))


#_(defsystem prod-system
  [:web (new-web-server (Integer. (env :documint-port))
                        (make-app))])

#_(defsystem prod-system
    [:web (new-web-server (Integer. (env :http-port))
                          (make-app))
     :repl-server (new-repl-server (Integer. (env :repl-port)))])
