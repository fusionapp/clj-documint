(ns documint.web
  "Documint web service.

  The API is REST-JSON with the following structure:

  POST   /sessions
    Create a new session.

    Contains hrefs for:
        * Performing an action;
        * Creating new session content;
        * Referencing itself.


  POST   /<session_uri>/perform
    Perform an action within a session. Action inputs and outputs are specific to
    each action being performed. Inputs and outputs are specified as URIs.

    {\"action\": \"some_action\",
     \"parameters\": { ... some parameters ... }}

    Contains hrefs for:
        * The contents of outputs (of which there may be more than one).


  POST   /<session_uri>/contents/
    Create new session content.

    Contains hrefs for:
        * Referencing itself.


  GET    /<session_uri>/contents/<content_id>
    Fetch the content of an output.


  DELETE /<session_uri>
    Remove a session and all its contents. "
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [liberator.core :refer [defresource]]
            [liberator.dev :refer [wrap-trace]]
            [bidi.bidi :as bidi]
            [bidi.ring :refer [make-handler]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [manifold.deferred :as d]
            [documint.content :as content]
            [documint.session :as session]
            [documint.actions :refer [perform-action]]
            [documint.util :refer [transform-map]]))


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
  (let [id          (get-in ctx [:request :params ::content-id])
        content     (session/get-content (::session ctx) id)]
    (if-not (nil? content)
      [true {:representation {:media-type (:content-type content)}
             ::content content}]
      [false {:representation {:media-type "application/json"}
              ::causes        [[:unknown-content "Unknown content identifier" id]]}])))


(defn- cause
  "Construct a cause map from a cause vector."
  ([type reason]
   (cause type reason nil))

  ([type reason description]
   {:type        type
    :reason      (if (instance? Exception reason)
                   (.toString reason)
                   reason)
    :description description}))


(defn- error-response
  "Convert a `:documint.web/causes` key into a map of causes and their
  reasons."
  [ctx]
  (log/error (:exception ctx) "Error handling a web request")
  (let [exc   (:exception ctx)
        info  (or (ex-data exc) ctx)
        causes (get info ::causes
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


(defresource session-index [{:keys [session-factory]}]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :handle-exception error-response
  :can-post-to-missing? false
  :post-redirect? true
  :post!
  (fn [ctx]
    {:location (session-uri ctx (session/new-session session-factory))}))


(defresource session-resource [{:keys [session-factory]}]
  :available-media-types ["application/json"]
  :allowed-methods [:get :delete]
  :handle-exception error-response
  :exists? (session? session-factory)
  :handle-not-found error-response
  :handle-ok
  (fn [{session ::session
        :as     ctx}]
    {:links {:self         (session-uri ctx session)
             :perform      (session-perform-uri ctx session)
             :post-content (content-index-uri ctx session)}})
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


(defresource session-perform [{:keys [session-factory]
                               :as state}]
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
    (let [make-uri (partial content-uri ctx)
          outputs  (perform-action action parameters session state)
          d        (d/chain outputs
                            #(transform-response make-uri %)
                            #(assoc {} ::response %))
          ; We deref here because Liberator doesn't really do async.
          response (deref d 10000 ::no-response)]
      (if (= ::no-response response)
        (throw (ex-info "Performing an action timed out"
                        {::causes [[:perform-timed-out action]]}))
        response)))
  :handle-created ::response)


(defresource content-index [{:keys [session-factory]}]
  :available-media-types ["*"]
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
      {:representation {:media-type "application/json"}
       ::response      {:links {:self (content-uri ctx content)}}}))
  :handle-created ::response)


(defresource content-resource [{:keys [session-factory]}]
  :available-media-types ["*"]
  :allowed-methods [:get]
  :handle-exception error-response
  :exists? (comp-decisions content?
                           (session? session-factory))
  :handle-not-found error-response
  :handle-ok #(get-in % [::content :stream]))


(defn route-handlers
  ""
  [state]
  {::session-index    (session-index state)
   ::session-resource (session-resource state)
   ::session-perform  (session-perform state)
   ::content-index    (content-index state)
   ::content-resource (content-resource state)})


(defn make-app
  "Create the web service application."
  [state]
  (let [handlers (route-handlers state)]
    (-> (make-handler routes handlers)
        #_(wrap-trace :header :ui)
        (wrap-defaults api-defaults))))
