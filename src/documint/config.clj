(ns documint.config
  "Documint configuration files."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as jio]
            [clojure.tools.logging :as log]
            [schema.core :as s]
            [environ.core :refer [env]]
            [documint.schema :refer [path-exists?]]
            [documint.util :refer [deep-merge]])
  (:import [java.io File]))


(def ^:private config-schema
  ""
  {:web-server     {(s/optional-key :port)     s/Int
                    (s/optional-key :tls-port) s/Int
                    (s/optional-key :tls-cert) s/Str}
   :keystore       {:path     path-exists?
                    :password s/Str}
   (s/optional-key
    :truststore)   {:path     path-exists?
                    :password s/Str}
   :signing        {:certificate-passwords {s/Keyword s/Str}}
   :renderer       {(s/optional-key :font-path) path-exists?
                    (s/optional-key :logging?)  s/Bool}
   (s/optional-key
    :renderer-fop) {(s/optional-key :xconf) path-exists?}
   })


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
  [^File file]
  (if (.exists file)
    (do
      (log/info "Reading config file"
                file)
      (json/read (jio/reader file) :key-fn keyword))
    (do
      (log/info "Skipping nonexistent config file"
                file)
      {})))


(defn- known-config-paths
  "Build a vector of known config file paths."
  []
  (vector (jio/file (user-home) ".config" "documint" "config.json")
          (jio/file (run-dir) "documint.config.json")))


(defn- merge-env
  "Merge environment variables into the config."
  [config]
  (let [{port     :documint-port
         tls-port :documint-tls-port} env]
    (cond-> config
      port     (assoc-in [:web-server :port] (Integer. port))
      tls-port (assoc-in [:web-server :tls-port] (Integer. tls-port)))))


(defn load-config
  "Load config files from all known paths if they exist and combine them."
  ([]
   (load-config (known-config-paths) {}))

  ([known-paths default-config]
   (log/info "Loading configuration")
   (->> known-paths
        (reduce (fn [config f]
                  (deep-merge config (parse-config f)))
                default-config)
        merge-env
        (s/validate config-schema))))
