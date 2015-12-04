(ns documint.content
  "Documint content storage.

  `IStorage` implements the public API for interacting with storage."
  (:require [clojure.java.io :as jio]
            [clojure.tools.logging :as log]))


(defrecord LocalContent [id session-id content-type file])


(defn- tmpdir
  "The temporary file directory looked up via the `java.io.tmpdir`
   system property."
  []
  (System/getProperty "java.io.tmpdir"))


(defn- create-temp-dir
  "Create a temporary directory."
  [parent prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            (if (instance? java.io.File parent)
              (.toPath parent)
              parent)
            prefix
            (into-array java.nio.file.attribute.FileAttribute []))))


(defn- create-temp-file
  "Create a file for the temporary storage of content data."
  [parent content-id]
  (java.io.File/createTempFile content-id nil parent))


(defn- delete-temp-dir
  "Delete the content temporary storage."
  [root]
  (doseq [path (.listFiles root)]
    (jio/delete-file path))
  (jio/delete-file root))


(defprotocol IStorage
  "Content storage."
  (destroy [this]
   "Destroy this storage.")

  (get-content [this content-id]
   "Retrieve the content of an entry from storage.

    Responses are a map containing `:stream` and `:content-type` keys, or the
    response is `nil` if no such content identifier exists.")

  (put-content [this session-id content-type readable]
   "Create a storage entry."))


(defrecord TemporaryFileStorage [next-id contents temp-dir]
  IStorage
  (destroy [this]
    (log/info "Destroying storage"
              {:temp-dir temp-dir})
    (delete-temp-dir temp-dir)
    (reset! contents {}))

  (get-content [this content-id]
    ; XXX: If we're storing the contents asynchronously, then we need some way
    ; to wait on this completing before reading it.
    (log/info "Retrieving content"
              {:id content-id})
    (if-let [entry (get @contents content-id)]
      {:stream       (jio/input-stream (:file entry))
       :content-type (:content-type entry)}
      nil))

  (put-content [this session-id content-type readable]
    (let [id    (next-id)
          f     (create-temp-file temp-dir id)
          entry (map->LocalContent {:id           id
                                    :session-id   session-id
                                    :content-type content-type
                                    :file         f})]
      ; XXX: Maybe this should happen asynchronously?
      (log/info "Storing content" entry)
      (log/spy (do
                 (jio/copy readable f)
                 (swap! contents assoc id entry)))
      entry)))


(defn temp-file-storage
  "An `IStorage` implementation backed by temporary files."
  ([next-id]
   (temp-file-storage next-id (jio/file (tmpdir))))

  ([next-id parent]
   (let [temp-dir (create-temp-dir parent "documint")]
     (.deleteOnExit temp-dir)
     (map->TemporaryFileStorage
      {:next-id next-id
       :contents (atom {})
       :temp-dir (create-temp-dir temp-dir "documint")}))))
