(ns documint.actions
  "Documint actions."
  (:require [manifold.deferred :as d]
            [schema.core :as s]
            [com.climate.claypoole :as cp]
            [documint.actions.interfaces :refer [perform schema]]
            [documint.actions.pdf :as pdf-actions]
            [documint.content :as content]
            [documint.util :refer [ptransform-map]]))


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
  [pool response]
  (future
    (ptransform-map
     pool
     (fn [x]
       (if (satisfies? content/IStorageEntry x)
         (content/realize-thunk x)
         x))
     response))
  response)


; XXX: This is not ideal, we should be sharing the threadpool with the rest of
; the application.
(def ^:private pool (cp/threadpool (cp/ncpus)))


(defn perform-action
  "Perform an `IAction` by name."
  [action-name parameters session]
  (if-let [action (get known-actions action-name)]
    (try
      (d/chain (perform action session (s/validate (schema action) parameters))
               (partial realize-response pool))
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
