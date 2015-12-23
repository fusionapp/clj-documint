(ns documint.config
  "Documint configuration files."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as jio]
            [clojure.tools.logging :as log]))


(def ^:private default-config {})


(defn- user-home
  "Get the system property for the user's home directory."
  []
  (System/getProperty "user.home"))


(defn- run-dir
  "Get the system property for the directory the application was invoked from."
  []
  (System/getProperty "user.dir"))


(defn parse-config
  "Parse a config file as JSON."
  [file]
  (if (.exists file)
    (do
      (log/info "Reading config file"
                file)
      (json/read (jio/reader file) :key-fn keyword))
    (do
      (log/info "Skipping nonexistent config file"
                file)
      {})))


(defn load-config
  "Load config files from all known paths if they exist and combine them."
  []
  (log/info "Loading configuration")
  (let [known-paths [(jio/file (user-home) ".config" "documint" "config.json")
                     (jio/file (run-dir) "documint.config.json")]]
    (log/spy (reduce
              (fn [config f]
                (merge config (parse-config f)))
              default-config
              known-paths))))
