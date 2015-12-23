(ns documint.actions.pdf
  "Documint PDF actions.

  A collection of `IAction` implementations for manipulating PDF documents."
  (:require [clojure.tools.logging :as log]
            [manifold.deferred :as d]
            [documint.session :as session]
            [documint.pdf :as pdf]
            [documint.actions :refer [IAction]]
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
    (perform [this {renderer :renderer} session {:keys [input base-uri]}]
      (d/chain (fetch-content input)
               (partial pdf/render-html renderer {:base-uri base-uri})
               vector
               (partial allocate-thunks session)
               (fn [content]
                 {:links {:result content}})))))


(def concatenate
  "Concatenate several PDF documents together.

  Parameters:
    `inputs`: A list of URIs to PDF documents."
  (reify IAction
    (perform [this state session {:keys [inputs]}]
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
    `breadth`: Widest part of the thumbnail in pixels, the shorter end will be
        scaled accordingly."
  (reify IAction
    (perform [this state session {:keys [input breadth]}]
      (d/chain (fetch-content input)
               (partial pdf/thumbnails breadth)
               (partial allocate-thunks session)
               (fn [contents]
                 {:links {:results contents}})))))


(def split
  "Split a PDF document into multiple documents.

  Parameters:
    `input`: URI to a PDF document.
    `page-groups`: A vector of vectors of page numbers, each top-level vector
        represents a document containing only those pages from the original document,
        in the order they are specified."
  (reify IAction
    (perform [this state session {:keys [input page-groups]}]
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
    (perform [this state session {:keys [input]}]
      (d/chain (fetch-content input)
               pdf/metadata
               (fn [body]
                 {:body body})))))
