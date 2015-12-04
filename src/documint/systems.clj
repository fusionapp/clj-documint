(ns documint.systems
  "Documint system definitions."
  (:require [system.core :refer [defsystem]]
            (system.components
             [jetty :refer [new-web-server]]
             #_[aleph :refer [new-web-server]])
            [environ.core :refer [env]]
            [documint.web :as web]
            [documint.render.flying-saucer :as saucer]
            [documint.session :refer [temp-file-session-factory]]
            [documint.actions :refer [register-actions!]]
            [documint.actions.pdf :as pdf-actions]))


(defonce -session-factory (temp-file-session-factory))


(defn- make-app
  ""
  []
  (register-actions!
   {"render-html" pdf-actions/render-html
    "concat"      pdf-actions/concatenate
    "thumbnails"  pdf-actions/thumbnails
    "split"       pdf-actions/split
    "metadata"    pdf-actions/metadata})
  ; XXX: don't hardcode the config
  (web/make-app
   {:renderer        (saucer/renderer {:font-path "/Users/jonathan/.fonts"})
    :session-factory -session-factory}))


(defsystem dev-system
  [:web (new-web-server (Integer. (env :documint-port))
                        (make-app))])


(defsystem prod-system
  [:web (new-web-server (Integer. (env :documint-port))
                        (make-app))])

#_(defsystem prod-system
    [:web (new-web-server (Integer. (env :http-port))
                          (make-app))
     :repl-server (new-repl-server (Integer. (env :repl-port)))])
