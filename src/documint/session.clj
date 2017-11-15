(ns documint.session
  "Documint sessions.

  `ISessionFactory` implements the public API for creation `ISession`s, which in
  turn implement the public API for interacting with sessions."
  (:require [clojure.tools.logging :as log]
            [manifold.time :refer [in minutes]]
            [iapetos.core :as prometheus]
            [documint.content :as content]
            [documint.util :refer [uuid-str]]
            [documint.metrics :refer [registry]]))


(defprotocol ISession
  "Session."
  (session-id [this]
    "Retrieve the identifier for this session.")

  (destroy [this]
    "Destroy this session and its storage.")

  (allocate-thunk [this thunk]
    "Allocate a `IStorageEntry` for a thunk.")

  (get-content [this content-id]
    "Retrieve the content of an entry from storage for this session.

    Responses are a map containing `:stream` and `:content-type` keys, or the
    response is `nil` if no such content identifier exists.")

  (put-content [this content-type readable]
    "Create a storage entry."))


(defrecord Session [id destroy storage]
  ISession
  (session-id [this]
    id)

  (destroy [this]
    (log/info "Destroying session"
              {:id id})
    (content/destroy storage)
    (when (destroy)
      (prometheus/dec (registry :documint/sessions-active-total))))

  (allocate-thunk [this thunk]
    (content/allocate-entry storage id thunk))

  (get-content [this content-id]
    (content/get-content storage content-id))

  (put-content [this content-type readable]
    (content/put-content storage id content-type readable)))


(defprotocol ISessionFactory
  "Session factory."
  (new-session [this]
    "Create a new session.")

  (get-session [this id]
    "Retrieve an existing session by `id`.")

  (destroy-session [this id]
    "Destroy a session."))


(defrecord SessionFactory [next-id sessions storage-factory ttl]
  ISessionFactory
  (new-session [this]
    (let [id      (next-id)
          session (map->Session {:id      id
                                 :destroy (fn -destroy []
                                            (when (contains? @sessions id)
                                              (swap! sessions dissoc id)))
                                 :storage (storage-factory)})]
      (swap! sessions assoc id session)
      (in ttl #(destroy session))
      (prometheus/inc (registry :documint/sessions-active-total))
      session))

  (get-session [this id]
    (get @sessions id)))


(defn temp-file-session-factory
  "Create an `ISessionFactory` instance backed by temporary files.

  Sessions and content for those sessions are not persisted."
  ([]
   (temp-file-session-factory (uuid-str)))

  ([next-id]
   (temp-file-session-factory next-id (minutes 10)))

  ([next-id ttl]
   (map->SessionFactory {:next-id         next-id
                         :sessions        (atom {})
                         :storage-factory #(content/temp-file-storage next-id)
                         :ttl             ttl})))
