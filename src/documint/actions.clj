(ns documint.actions
  "Documint actions."
  (:require [manifold.deferred :as d]
            [documint.actions.interfaces :refer [perform]]
            [documint.actions.pdf :as pdf-actions]
            [documint.content :as content]
            [documint.util :refer [transform-map]]))


(def ^:private known-actions
  {"render-html" pdf-actions/render-html
   "concat"      pdf-actions/concatenate
   "thumbnails"  pdf-actions/thumbnails
   "split"       pdf-actions/split
   "metadata"    pdf-actions/metadata
   "sign"        pdf-actions/sign})


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
    (-> (perform action session parameters)
        (d/chain
         (fn [response]
           (future
             (transform-map
              (fn [x]
                (when (satisfies? content/IStorageEntry x)
                  (content/realize-thunk x))
                x)
              response))
           response)))
    (throw (ex-info "Unknown action"
                    {:causes [[:unknown-action action-name]]}))))
