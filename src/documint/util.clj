(ns documint.util
  "Documint utility functions."
  (:require [clojure.tools.logging :as log]
            [clj-uuid :as uuid]
            [aleph.http :as http]
            [manifold.deferred :as d]
            [com.climate.claypoole :as cp])
  (:import [java.security KeyStore]))


(defn uuid-str
  "Produce factory functions that construct UUIDs represented as strings."
  ([]
   (uuid-str uuid/v4))

  ([f]
   (fn []
     (uuid/to-string (f)))))


(defn map-vals
  "Map only the values in a map to produce a new map."
  [f m]
  (reduce-kv #(assoc %1 %2 (f %3)) {} m))


(defn fetch-content
  "Fetch the content of a URI and present it with the same structure as stored
  content."
  [uri]
  (log/info "Fetching content"
            {:uri uri})
  (d/chain (http/get uri)
           (fn [response]
             {:stream (:body response)
              :content-type (get-in response [:headers :content-type])})))


(defn wait-close
  "Close `closeable` when the items being waited on have all been done.

  Returns a function taking one argument, the item from the `waiting` set, that
  has completed."
  [^java.io.Closeable closeable waiting]
  (let [waiting (atom (set waiting))
        done    (fn [item]
                  (swap! waiting disj item)
                  (when (empty? @waiting)
                    (.close closeable)))]
    done))


(defn ptransform-map
  "Recursively transform every primitive value in map `m` by passing it to `f`,
  while retaining the structure of `m`. Sequential values are transformed in
  parallel."
  [pool f m]
  (map-vals
   (fn [v]
     (cond
       (map? v)        (ptransform-map pool f v)
       (sequential? v) (cp/pmap pool f v)
       :else           (f v)))
   m))


(defn transform-map
  "Recursively transform every primitive value in map `m` by passing it to `f`,
  while retaining the structure of `m`."
  [f m]
  (ptransform-map :serial f m))


(defn open-keystore
  "Open a `Keystore`."
  [f ^String password]
  (log/info "Opening keystore"
            {:f f})
  (with-open [input (clojure.java.io/input-stream f)]
    (let [keystore (KeyStore/getInstance (KeyStore/getDefaultType))]
      (try
        (.load keystore input (.toCharArray password))
        (catch Exception e
          (throw (ex-info "Invalid keystore"
                          {:causes [[:invalid-keystore (.getMessage e)]]}))))
      keystore)))


(defn deep-merge
  "Deeply merge maps."
  [& maps]
  (apply merge-with deep-merge maps))


(defmacro time-body-ms
  "Time the specified expression and return a vector containing execution time
  (in milliseconds) and the expression result."
  [expr]
  (let [sym (= (type expr) clojure.lang.Symbol)]
    `(let [start#  (. System (nanoTime))
           return# ~expr
           res#    (if ~sym
                     (resolve '~expr)
                     (resolve (first '~expr)))]
       [(/ (double (- (. System (nanoTime)) start#)) 1000000.0) return#])))


(defn swap-vals!
  "Like swap-vals! in Clojure 1.9."
  [atom f & args]
  (let [m       (volatile! nil)
        new-val (apply swap! atom
                       (fn [val & args]
                         (vreset! m val)
                         (apply f val args))
                       args)]
    [@m new-val]))


(defn counter
  "An identity generator that uses ever increasing numbers.

  Use in place of UUIDs when testing."
  [n]
  (let [counter (atom n)]
    (fn []
      (str (swap! counter inc)))))
