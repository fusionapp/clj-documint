(ns user
  (:require [system.repl :refer [init start stop go reset]]
            [documint.systems :refer [dev-system]]))


(system.repl/set-init! #'dev-system)
