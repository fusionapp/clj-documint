(ns documint.schema
  "Commonly used schemas in Documint."
  (:require [schema.core :as s]))


(def path-exists?
  "Does the `String` value represent a valid file-system path?"
  (s/constrained
   s/Str
   (fn [path]
     (.exists (clojure.java.io/file path)))
   "path-exists?"))


(def uri?
  "Does the `String` value represent a valid HTTP or HTTPS URI?"
  (s/constrained
   s/Str
   (fn [uri]
     (try
       (->> (java.net.URI. uri)
            (.getScheme)
            (clojure.string/lower-case)
            (contains? #{"http" "https"}))
       (catch Exception e
         false)))
   "uri?"))
