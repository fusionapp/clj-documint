(ns documint.actions
  "Documint actions."
  (:require [manifold.deferred :as d]
            [schema.core :as s]
            [documint.actions.interfaces :refer [perform schema]]
            [documint.actions.pdf :as pdf-actions]
            [documint.content :as content]
            [documint.util :refer [transform-map]]))


(def ^:private known-actions
  {"render-html" pdf-actions/render-html
   "concat"      pdf-actions/concatenate
   "thumbnails"  pdf-actions/thumbnails
   "split"       pdf-actions/split
   "metadata"    pdf-actions/metadata
   "sign"        pdf-actions/sign
   "crush"       pdf-actions/crush})


(defn- realize-response
  "Realize the result of `IAction/perform`.

  Any `IStorageEntry` values will be realized via
  `documint.content/realize-thunk`. Returns a map with the same structure as the
  original response."
  [response]
  (future
    (transform-map
     (fn [x]
       (if (satisfies? content/IStorageEntry x)
         (content/realize-thunk x)
         x))
     response))
  response)


(defn perform-action
  "Perform an `IAction` by name."
  [action-name parameters session]
  (if-let [action (get known-actions action-name)]
    (try
      (d/chain (perform action session (s/validate (schema action) parameters))
               realize-response)
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (throw
           (if (= (:type data) :schema.core/error)
             (ex-info "Schema validation failure"
                      {:causes [[:validation-failure
                                 (select-keys data [:error])]]})
             e)))))
    (throw (ex-info "Unknown action"
                    {:causes [[:unknown-action action-name]]}))))
