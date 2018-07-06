(ns documint.core
  (:require [clojure.java.io :as io]
            [system.repl :refer [init start stop go reset]]
            [documint.systems :refer [prod-system]])
  (:gen-class))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (system.repl/set-init! #'prod-system)
  (go))
