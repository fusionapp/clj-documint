(ns documint.content
  "Documint content storage.

  `IStorage` implements the public API for interacting with storage."
  (:require [clojure.java.io :as jio]
            [clojure.tools.logging :as log]
            [ring.util.io :refer [piped-input-stream]]
            [manifold.deferred :as d])
  (:import [java.io File]))


(defn- tmpdir
  "The temporary file directory looked up via the `java.io.tmpdir`
   system property."
  []
  (System/getProperty "java.io.tmpdir"))


(defn- ^File create-temp-dir
  "Create a temporary directory."
  [^File parent prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            (if (instance? java.io.File parent)
              (.toPath parent)
              parent)
            prefix
            (into-array java.nio.file.attribute.FileAttribute []))))


(defn- ^File create-temp-file
  "Create a file for the temporary storage of content data."
  [parent content-id]
  (java.io.File/createTempFile content-id nil parent))


(defn- delete-temp-dir
  "Delete the content temporary storage."
  [^File root]
  (doseq [path (.listFiles root)]
    (jio/delete-file path))
  (jio/delete-file root))


(defprotocol IStorageEntry
  ""
  (content-id [this]
    "Identifier for this content.")

  (session-id [this]
    "Session identifier for this content.")

  (entry-content [this]
    "Fetch the content for this entry.

    The result is a deferred map containing `:stream` and `:content-type` keys,
    or the response is `nil` if no such content identifier exists.")

  (realize-thunk [this]
    "Realize the entry's thunk."))


(defrecord LocalEntry [id session-id store-fn file deferred-result thunk]
  IStorageEntry
  (content-id [this]
    id)

  (session-id [this]
    session-id)

  (entry-content [this]
    (-> deferred-result
        (d/chain
         (fn [{:keys [content-type stored]}]
           {:content-type content-type
            :stream       (jio/input-stream
                           (case content-type
                             nil (throw (ex-info "Empty content entry"
                                                 {:id id}))
                             stored))}))))

  (realize-thunk [this]
    (log/info "Realizing thunk content")
    (store-fn
     (piped-input-stream
      (fn [output]
        (try
          (let [content-type (thunk output)]
            (log/info "Successfully realized thunk"
                      {:content-type content-type})
            (d/success! deferred-result
                        {:content-type content-type
                         :stored       file}))
          (catch Exception e
            (log/error e "Failed realizing thunk")
            (d/error! deferred-result e))))))
    this))


(defprotocol IStorage
  "Content storage."
  (destroy [this]
    "Destroy this storage.")

  (get-content [this entry-id]
    "Retrieve the content of an entry from storage.

     The result is a deferred map containing `:stream` and `:content-type` keys,
     or the response is `nil` if no such content identifier exists.")

  (allocate-entry [this session-id thunk]
    "Allocate a content entry, the result is a `IStorageEntry`.")

  (put-content [this session-id content-type readable]
    "Allocate and realize a content entry."))


(defrecord TemporaryFileStorage [next-id contents ^File temp-dir]
  IStorage
  (destroy [this]
    (when (.exists temp-dir)
      (log/info "Destroying storage"
                {:temp-dir temp-dir})
      (delete-temp-dir temp-dir)
      (reset! contents {})))

  (allocate-entry [this session-id thunk]
    (let [id       (next-id)
          f        (create-temp-file temp-dir id)
          store-fn (fn [input]
                     (jio/copy input f))
          entry    (map->LocalEntry {:id              id
                                     :session-id      session-id
                                     :deferred-result (d/deferred)
                                     :thunk           thunk
                                     :store-fn        store-fn
                                     :file            f})]
      (log/info "Allocated content entry" entry)
      (swap! contents assoc id entry)
      entry))

  (get-content [this entry-id]
    (log/info "Retrieving entry content"
              {:id entry-id})
    (if-let [entry (get @contents entry-id)]
      (entry-content entry)
      nil))

  (put-content [this session-id content-type readable]
    (let [entry (allocate-entry this
                                session-id
                                (fn [output]
                                  (jio/copy readable output)
                                  content-type))]
      (realize-thunk entry))))


(defn temp-file-storage
  "An `IStorage` implementation backed by temporary files."
  ([next-id]
   (temp-file-storage next-id (jio/file (tmpdir))))

  ([next-id parent]
   (let [temp-dir (create-temp-dir parent "documint")]
     (.deleteOnExit temp-dir)
     (map->TemporaryFileStorage
      {:next-id  next-id
       :contents (atom {})
       :temp-dir (create-temp-dir temp-dir "documint")}))))
