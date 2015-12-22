(ns documint.actions
  "Documint actions."
  (:require [clojure.tools.logging :as log]
            [manifold.deferred :as d]
            [documint.content :as content]
            [documint.util :refer [transform-map]]))


(defprotocol IAction
  "Documint action."
  (perform [this state session parameters]
    "Perform this action."))


(defonce -known-actions (atom {}))


(defn register-actions!
  "Register known actions.

  `actions` is a map of action names to `IAction` implementations to be merged
  with the current known actions."
  [actions]
  (swap! -known-actions merge actions))


(defn perform-action
  "Perform an `IAction` by name."
  [action-name parameters session state]
  (let [action (get @-known-actions action-name)]
    (-> (perform action state session parameters)
        (d/chain
         (fn [response]
           (future
             (transform-map
              (fn [x]
                (when (satisfies? content/IStorageEntry x)
                  (content/realize-thunk x))
                x)
              response))
           response)))))
