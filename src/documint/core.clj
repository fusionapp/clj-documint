(ns documint.core
  (:require [clojure.java.io :as io]
            #_[documint.flying-saucer :as flying-saucer]
            #_[reloaded.repl :refer [system init start stop go reset]]
            #_[documint.systems :refer [prod-system]])
  (:gen-class))

(set! *warn-on-reflection* true)


(defn -main
  "I don't do a whole lot ... yet."
  [path & args]
  #_(reloaded.repl/set-init! prod-system)
  #_(go)
  #_(with-open [input  (io/input-stream path)
              output System/out]
    (flying-saucer/render input output)))
