(ns documint.actions.interfaces
  "Documint action protocols.")


(defprotocol IAction
  "Documint action."
  (schema [this]
    "Schema to use for validating this action's parameters.")

  (perform [this get-content session parameters]
    "Perform this action.

     Given an `ISession` instance and a `map` of parameters, specific to each
     action, return a deferred `map` to be serialized as JSON; any values that
     satisfy the `IStorageEntry` protocol will be converted to URIs."))
