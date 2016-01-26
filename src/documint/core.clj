(ns documint.core
  (:require [clojure.java.io :as io]
            [reloaded.repl :refer [system init start stop go reset]]
            [documint.systems :refer [prod-system]])
  (:gen-class))

(set! *warn-on-reflection* true)


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (reloaded.repl/set-init! prod-system)
  (go))
