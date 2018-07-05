(ns documint.actions
  "Documint actions."
  (:require [manifold.deferred :as d]
            [schema.core :as s]
            [com.climate.claypoole :as cp]
            [iapetos.core :as prometheus]
            [documint.actions.interfaces :refer [perform schema]]
            [documint.actions.pdf :as pdf-actions]
            [documint.content :as content]
            [documint.util :refer [ptransform-map]]
            [documint.metrics
             :refer [registry]
             :as metrics]))


(def ^:private known-actions
  {"render-html"        pdf-actions/render-html
   "render-legacy-html" pdf-actions/render-legacy-html
   "concatenate"        pdf-actions/concatenate
   "thumbnails"         pdf-actions/thumbnails
   "split"              pdf-actions/split
   "metadata"           pdf-actions/metadata
   "sign"               pdf-actions/sign
   "crush"              pdf-actions/crush
   "stamp"              pdf-actions/stamp})


(defn- realize-response
  "Realize the result of `IAction/perform`.

  Any `IStorageEntry` values will be realized via
  `documint.content/realize-thunk`. Returns a map with the same structure as the
  original response."
  [pool response]
  (d/future
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
    (prometheus/with-failure-counter
      (registry :documint/actions-errored-total {:action action-name})
      (try
        (->
         (d/chain (perform action session (s/validate (schema action) parameters))
                  (partial realize-response pool))
         (metrics/async-duration
          (registry :documint/actions-seconds {:action action-name}))
         (metrics/async-activity-counter
          (registry :documint/actions-running-total {:action action-name}))
         (metrics/async-counters
          {:total (registry :documint/actions-total {:action action-name})
           :success (registry :documint/actions-succeeded-total {:action action-name})
           :failure (registry :documint/actions-failed-total {:action action-name})}))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (throw
             (if (= (:type data) :schema.core/error)
               (ex-info "Schema validation failure"
                        {:causes [[:validation-failure
                                   {:error (prn-str (:error data))}]]})
               e))))))
    (throw (ex-info "Unknown action"
                    {:causes [[:unknown-action action-name]]}))))
