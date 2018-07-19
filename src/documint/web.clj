(ns documint.web
  "Documint web service."
  (:refer-clojure :exclude [if-let when-let])
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [better-cond.core :refer [if-let when-let]]
            [liberator.core :refer [defresource]]
            [liberator.dev :refer [wrap-trace]]
            [bidi.bidi :as bidi]
            [bidi.ring :refer [make-handler]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [iapetos.collector.ring :as ring]
            [manifold.deferred :as d]
            [documint.content :as content]
            [documint.session :as session]
            [documint.actions :refer [perform-action]]
            [documint.util :refer [transform-map fetch-content]]
            [documint.metrics :refer [registry]])
  (:import [org.eclipse.jetty.server SslConnectionFactory]))


(def routes
  "API routes definition."
  ["/sessions/" {""                                       ::session-index
                 [::session-id]                           ::session-resource
                 [::session-id "/perform"]                ::session-perform
                 [::session-id "/contents/"]              ::content-index
                 [::session-id "/contents/" ::content-id] ::content-resource}])


(defn session?
  "Look up the session in a request.

  If found, the session is associated with the `:documint.web/session` key."
  [session-factory]
  (fn [ctx]
    (let [id      (get-in ctx [:request :params ::session-id])
          session (session/get-session session-factory id)]
      (if-not (nil? session)
        [true {::session session}]
        [false {:representation {:media-type "application/json"}
                ::causes        [[:unknown-session "Unknown session identifier" id]]}]))))


(defn content?
  "Look up the content for a session in the request.

  If found, the content is associated with the `:documint.web/content` key."
  [ctx]
  (let [id               (get-in ctx [:request :params ::content-id])
        deferred-content (session/get-content (::session ctx) id)
        content          (try
                           (deref deferred-content 10000 ::no-response)
                           (catch Exception e
                             e))]
    (cond
      (= ::no-response content)
      [false {:representation {:media-type "application/json"}
              ::causes        [[:content-timed-out id]]}]

      (nil? content)
      [false {:representation {:media-type "application/json"}
              ::causes        [[:unknown-content "Unknown content identifier" id]]}]

      (instance? Exception content)
      [false {:representation {:media-type "application/json"}
              :exception      content}]

      :else
      [true {:representation {:media-type (:content-type content)}
             ::content       content}])))


(defn- cause
  "Construct a cause map from a cause vector."
  ([type reason]
   (cause type reason nil))

  ([type reason description]
   {:type        type
    :reason      (if (instance? Exception reason)
                   (.toString ^Exception reason)
                   reason)
    :description description}))


(defn- error-response
  "Convert a `:documint.web/causes` key into a map of causes and their
  reasons."
  [ctx]
  (log/error (:exception ctx) "Error handling a web request")
  (let [exc    (:exception ctx)
        causes (or (if exc
                     (get (ex-data exc) :causes)
                     (get ctx ::causes))
                   [[:unhandled-exception exc]])]
    {:causes (map (partial apply cause) causes)}))


(defn- uri-for
  "Construct a URI given a Ring request map and a URI path."
  [{req :request} path]
  (.toString (java.net.URI.
              (name (:scheme req))
              nil
              (:server-name req)
              (:server-port req)
              path
              nil
              nil)))


(defn local-uri?
  "Return the URI path if the URI is local to this instance."
  [{req :request}]
  (fn [uri]
    (let [uri    (java.net.URI. uri)
          scheme (.getScheme uri)
          host   (.getHost uri)
          port   (.getPort uri)]
      (when (and (= (name (:scheme req)) scheme)
                 (= (:server-name req) host)
                 (= (:server-port req) (if (neg? port)
                                         ({"http"  80
                                           "https" 443} scheme)
                                         port)))
        (.getPath uri)))))


(defn near-location
  "Find the coordinates for the near location, in the form of
  `[session-id content-id]`, if possible."
  [path]
  (when-let [path       path
             m          (bidi/match-route routes path)
             ps         (when (= ::content-resource (:handler m))
                          (:route-params m))
             session-id (::session-id ps)
             content-id (::content-id ps)]
    [session-id content-id]))


(defn local-fetcher
  "Create a function to retrieve local content that does not use HTTP."
  [session-factory]
  (fn fetch-local [[sid cid]]
    (when-let [session (session/get-session session-factory sid)
               content (session/get-content session cid)]
      content)))


(defn content-getter
  "Create a function to retrieve session content.

  Accepts single or multiple URIs to fetch, `fetch-near` is tried when `near?`
  indicates a URI is near to this instance, falling back to `fetch-far`."
  [near? fetch-near fetch-far]
  (letfn [(get-one [uri]
            (if-let [loc     (near-location (near? uri))
                     content (fetch-near loc)]
              content
              (fetch-far uri)))]
    (fn get-content [uri]
      (if (sequential? uri)
        (apply d/zip (map get-one uri))
        (get-one uri)))))


(defn- session-uri
  "Construct the URI for a session resource."
  ([ctx session]
   (uri-for ctx (bidi/path-for routes
                               ::session-resource
                               ::session-id (session/session-id session)))))


(defn- session-perform-uri
  "Construct the URI for a session perform resource."
  ([ctx session]
   (uri-for ctx (bidi/path-for routes
                               ::session-perform
                               ::session-id (session/session-id session)))))


(defn- content-index-uri
  "Construct the URI for a content index."
  ([ctx session]
   (uri-for ctx (bidi/path-for routes
                               ::content-index
                               ::session-id (session/session-id session)))))


(defn- content-uri
  "Construct the URI for a content resource."
  ([ctx content]
   (uri-for ctx (bidi/path-for routes
                               ::content-resource
                               ::session-id (content/session-id content)
                               ::content-id (content/content-id content)))))


(defn parse-json
  "Parse a Liberator request's body as JSON."
  [key ctx]
  (try
    [false {key (-> ctx
                    (get-in [:request :body])
                    clojure.java.io/reader
                    (json/read :key-fn keyword))}]
    (catch Exception e
      [true {:representation {:media-type "application/json"}
             ::causes        [[:invalid-json e]]}])))


(defn- normalize-decision-result
  "Convert a decision result to it's canonical form."
  [result]
  (cond
    (nil? result)    [false {}]
    (false? result)  [false result]
    (vector? result) result))


(defn- comp-decisions
  "Compose several Liberator decision functions together.

  Short-circuits upon the first failed decision.

  Note: This only works for decisions whose failure value is `false`.
  "
  [& fs]
  (fn [ctx]
    (reduce
     (fn [[success?' prev-result] f]
       (let [[success? result] (normalize-decision-result
                                (f (merge ctx prev-result)))]
         (if success?
           [success? (merge prev-result result)]
           (reduced [success? result]))))
     [true ctx] (reverse fs))))


(defresource session-index [session-factory]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :handle-exception error-response
  :can-post-to-missing? false
  :post-redirect? true
  :post!
  (fn [ctx]
    {:location (session-uri ctx (session/new-session session-factory))}))


(defresource session-resource [session-factory]
  :available-media-types ["application/json"]
  :allowed-methods [:get :delete]
  :handle-exception error-response
  :exists? (session? session-factory)
  :handle-not-found error-response
  :handle-ok
  (fn [{session ::session
        :as     ctx}]
    {:links {:self          (session-uri ctx session)
             :perform       (session-perform-uri ctx session)
             :store-content (content-index-uri ctx session)}})
  :delete!
  (fn [{session ::session}]
    (session/destroy session)))


(defn- transform-response
  "Transform an action response map."
  [make-uri response]
  (transform-map
   (fn [v]
     (if (satisfies? content/IStorageEntry v)
       (make-uri v)
       v))
   response))


(defresource session-perform [session-factory]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :handle-exception error-response
  :malformed? #(parse-json ::data %)
  :handle-malformed error-response
  :exists? (session? session-factory)
  :handle-not-found error-response
  :can-post-to-missing? false
  :post!
  (fn [{session              ::session
        {:keys [action
                parameters]} ::data
        :as                  ctx}]
    (let [get-content (content-getter
                       (local-uri? ctx)
                       (local-fetcher session-factory)
                       fetch-content)
          make-uri    (partial content-uri ctx)
          outputs     (perform-action action parameters session get-content)
          d           (d/chain outputs
                               #(transform-response make-uri %)
                               #(assoc {} ::response %))
          ;; We deref here because Liberator doesn't really do async.
          response    (deref d 30000 ::no-response)]
      (if (= ::no-response response)
        (throw (ex-info "Performing an action timed out"
                        {:causes [[:perform-timed-out action]]}))
        response)))
  :handle-created ::response)


(defresource content-index [session-factory]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :handle-exception error-response
  :exists? (session? session-factory)
  :handle-not-found error-response
  :can-post-to-missing? false
  :post!
  (fn [{session ::session
        :as     ctx}]
    (let [body         (get-in ctx [:request :body])
          content-type (get-in ctx [:request :headers "content-type"]
                               "application/octet-stream")
          content      (session/put-content session content-type body)]
      {::response {:links {:self (content-uri ctx content)}}}))
  :handle-created ::response)


(defresource content-resource [session-factory]
  :available-media-types ["*"]
  :allowed-methods [:get]
  :handle-exception error-response
  :exists? (comp-decisions content?
                           (session? session-factory))
  :handle-not-found error-response
  :handle-ok #(get-in % [::content :stream]))


(defn route-handlers
  ""
  [session-factory]
  {::session-index    (session-index session-factory)
   ::session-resource (session-resource session-factory)
   ::session-perform  (session-perform session-factory)
   ::content-index    (content-index session-factory)
   ::content-resource (content-resource session-factory)})


(defn- path-fn
  "Describe a request path according to a route map"
  [routes]
  (fn [{uri :uri}]
    (pr-str (:handler (bidi/match-route routes uri)))))


(defrecord App [handler session-factory]
  component/Lifecycle
  (start [this]
    (let [handlers (route-handlers session-factory)]
      (assoc this :handler
             (-> (make-handler routes handlers)
                 #_(wrap-trace :header :ui)
                 (wrap-defaults api-defaults)
                 (ring/wrap-metrics registry {:path "/metrics"
                                              :path-fn (path-fn routes)})))))

  (stop [this]
    this))


(defn new-app
  []
  (map->App {}))


(defn- configure-ssl-connector
  "Configure the SSL context factory.

  Primarily we want to set the certificate alias and remove any HTTP
  connectors."
  [cert-alias http? server]
  (doseq [connector (.getConnectors server)]
    (if-let [ssl-context-factory
             (some-> connector
                     (.getConnectionFactory SslConnectionFactory)
                     .getSslContextFactory)]
      (.setCertAlias ssl-context-factory cert-alias)
      ; We need this since the `http?` ring-jetty-adapter option is not in a
      ; release yet.
      (when-not http?
        (.removeConnector server connector)))))


(defn jetty-options
  "ring-jetty-adapter options."
  [keystore
   truststore
   {{:keys [port tls-port tls-cert]} :web-server
    {key-password :password}         :keystore
    {trust-password :password}       :truststore}]
  (let [http? (boolean port)
        tls?  (boolean tls-port)]
    (cond-> {:join? false
             :ssl?  tls?}
      http?      (conj {:port port})
      tls?       (conj {:configurator (partial configure-ssl-connector tls-cert http?)
                        :ssl-port     tls-port})
      keystore   (conj {:keystore     keystore
                        :key-password key-password})
      truststore (conj {:client-auth    :need
                        :truststore     truststore
                        :trust-password trust-password}))))
