(ns documint.util
  "Documint utility functions."
  (:require [clojure.tools.logging :as log]
            [clj-uuid :as uuid]
            [aleph.http :as http]
            [manifold.deferred :as d]))


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


(defn fetch-multiple-contents
  "Fetch the contents of multiple URIs. See `fetch-content`."
  [uris]
  (apply d/zip (map fetch-content uris)))


(defn wait-close
  "Close `closeable` when the items being waited on have all been done.

  Returns a function taking one argument, the item from the `waiting` set, that
  has completed."
  [closeable waiting]
  (let [waiting (atom (set waiting))
        done    (fn [item]
                  (swap! waiting disj item)
                  (when (empty? @waiting)
                    (.close closeable)))]
    done))


(defn transform-map
  "Recursively transform every primitive value in map `m` by passing it to `f`,
  while retaining the structure of `m`."
  [f m]
  (map-vals
   (fn [v]
     (cond
       (map? v)        (transform-map f v)
       (sequential? v) (map f v)
       :else           (f v)))
   m))
