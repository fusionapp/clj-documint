(ns documint.actions
  "Documint actions.")


(defprotocol IAction
  ""
  (perform [this state session parameters]
   ""))


(defonce -known-actions (atom {}))


(defn register-actions!
  "Register known actions.

  `actions` is a map of action names to `IAction` implementations to be merged
  with the current known actions."
  [actions]
  (swap! -known-actions merge actions))


(defn perform-action
  "Perform an `IAction` by name."
  [action parameters session state]
  (perform (get @-known-actions action) state session parameters))
