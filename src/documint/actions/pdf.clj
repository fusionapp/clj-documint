(ns documint.actions.pdf
  "Documint PDF actions.

  A collection of `IAction` implementations for manipulating PDF documents."
  (:require [system.repl :refer [system]]
            [manifold.deferred :as d]
            [schema.core :as s]
            [documint.session :as session]
            [documint.pdf :as pdf]
            [documint.actions.interfaces :refer [IAction]]
            [documint.schema :refer [uri?]]
            [documint.util :refer [fetch-content fetch-multiple-contents]]))


(defn allocate-thunks
  "Allocate storage entries for multiple thunks."
  [session thunks]
  (map (partial session/allocate-thunk session) thunks))


(def render-html
  "Render an HTML document to a PDF document.

  Parameters:
    `input`: URI to the HTML content to render.
    `base-uri`: Base URI to use when resolving relative URIs."
  (reify IAction
    (schema [this]
      {:input                     uri?
       (s/optional-key :base-uri) uri?})

    (perform [this session {:keys [input base-uri]}]
      (d/chain (fetch-content input)
               (partial pdf/render-html
                        (:renderer/flying-saucer system)
                        {:base-uri base-uri})
               vector
               (partial allocate-thunks session)
               (fn [content]
                 {:links {:result content}})))))


(def render-legacy-html
  "Render an HTML document to a PDF document using CSS2XSLFO and Apache FOP.

  Parameters:
    `input`: URI to the HTML content to render.
    `base-uri`: Base URI to use when resolving relative URIs."
  (reify IAction
    (schema [this]
      {:input                     uri?
       (s/optional-key :base-uri) uri?})

    (perform [this session {:keys [input base-uri]}]
      (d/chain (fetch-content input)
               (partial pdf/render-html
                        (:renderer/fop system)
                        {:base-uri base-uri})
               vector
               (partial allocate-thunks session)
               (fn [content]
                 {:links {:result content}})))))


(def concatenate
  "Concatenate several PDF documents together.

  Parameters:
    `inputs`: A list of URIs to PDF documents."
  (reify IAction
    (schema [this]
      {:inputs [uri?]})

    (perform [this session {:keys [inputs]}]
      (d/chain (fetch-multiple-contents inputs)
               pdf/concatenate
               vector
               (partial allocate-thunks session)
               (fn [content]
                 {:links {:result content}})))))


(def thumbnails
  "Generate JPEG thumbnails for a PDF document.

  Parameters:
    `input`: URI to a PDF document.
    `dpi`: Pixel density of the thumbnail."
  (reify IAction
    (schema [this]
      {:input uri?
       :dpi   long})

    (perform [this session {:keys [input dpi]}]
      (d/chain (fetch-content input)
               (partial pdf/thumbnails dpi)
               (partial allocate-thunks session)
               (fn [contents]
                 {:links {:results contents}})))))


(def ^:private page-name-flat
  (s/conditional
   integer? s/Int
   string? (s/enum "first"
                   "last")))

(def ^:private page-name
  (s/conditional
   vector? [(s/one page-name-flat "start") (s/one page-name-flat "end")]
   :else   page-name-flat))

(def split
  "Split a PDF document into multiple documents.

  Parameters:
    `input`: URI to a PDF document.
    `page-groups`: A vector of vectors, representing the output documents. Each
        element should itself be a vector that contains any of the following to
        represent the pages of the document:

        - integer: 1-based page index;
        - string: Page location such as \"first\" or \"last\";
        - 2-vector: A start and end page reference (of either preceeding type),
          representing an inclusive page span, which may also run backwards."
  (reify IAction
    (schema [this]
      {:input       uri?
       :page-groups [[page-name]]})

    (perform [this session {:keys [input page-groups]}]
      (d/chain (fetch-content input)
               (partial pdf/split page-groups)
               (partial allocate-thunks session)
               (fn [contents]
                 {:links {:results contents}})))))


(def metadata
  "Retrieve metadata from a PDF document.

  Parameters:
    `input`: URI to a PDF document."
  (reify IAction
    (schema [this]
      {:input uri?})

    (perform [this session {:keys [input]}]
      (d/chain (fetch-content input)
               pdf/metadata
               (fn [body]
                 {:body body})))))


(def sign
  "Digitally sign one or more PDF documents.

  Parameters:
    `inputs`: Vector of URIs to PDF documents to be digitally signed.
    `certificate-alias`: Alias of the certificate in the keystore to use for
    signing.
    `reason`: PDF signature reason."
  (reify IAction
    (schema [this]
      {:inputs            [uri?]
       :certificate-alias s/Str
       :location          s/Str
       :reason            s/Str})
    (perform [this session {:keys [inputs certificate-alias location reason]}]
      (d/chain (fetch-multiple-contents inputs)
               (partial pdf/sign (:signer system) certificate-alias location reason)
               (partial allocate-thunks session)
               (fn [contents]
                 {:links {:results contents}})))))


(def crush
  "Compress a PDF document according to a specific compression profile.

  Parameters:
    `input`: URI to a PDF document to be compressed.
    `compression-profile`: Compression profile to use, possible choices are
    \"text\", \"photo-grey\", \"photo\". "
  (reify IAction
    (schema [this]
      {:input               uri?
       :compression-profile (s/enum "text" "photo-grey" "photo")})

    (perform [this session {:keys [input compression-profile]}]
      (d/chain (fetch-content input)
               (partial pdf/crush (keyword compression-profile))
               vector
               (partial allocate-thunks session)
               (fn [content]
                 {:links {:result content}})))))


(def stamp
  "Stamp one or more documents with a watermark document.

  Parameters:
    `inputs`: URIs to PDF documents to be stamped.
    `watermark`: URI to a PDF document to use as the stamp."
  (reify IAction
    (schema [this]
      {:inputs            [uri?]
       :watermark         uri?})
    (perform [this session {:keys [inputs watermark]}]
      (d/chain (fetch-multiple-contents (cons watermark inputs))
               (fn [[watermark & inputs]]
                 (pdf/stamp watermark inputs))
               (partial allocate-thunks session)
               (fn [contents]
                 {:links {:results contents}})))))
