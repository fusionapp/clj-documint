(ns documint.actions.interfaces
  "")


(defprotocol IAction
  ""
  (perform [this session parameters]
   "")

  (respond [this result]
   ""))
