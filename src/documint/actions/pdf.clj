(ns documint.actions.pdf
  "Documint PDF actions.

  A collection of `IAction` implementations for manipulating PDF documents."
  (:require [manifold.deferred :as d]
            [documint.render :as render]
            [documint.session :as session]
            [documint.pdf :as pdf]
            [documint.actions :refer [IAction]]
            [documint.util :refer [fetch-content fetch-multiple-contents]]))


(defn- put-multiple-contents
  "Store multiple contents of the same content-type in a session.

  Returns a seq of the stored contents."
  [session content-type streams]
  (map (partial session/put-content session content-type)
       streams))


(def render-html
  "Render an HTML document to a PDF document.

  Parameters:
    `input`: URI to the HTML content to render.
    `base-uri`: Base URI to use when resolving relative URIs."
  (reify IAction
    (perform [this {renderer :renderer} session {:keys [input base-uri]}]
      (d/chain (fetch-content input)
               (partial render/render-html renderer {:base-uri base-uri})
               (fn [stream]
                 {:links
                  {:result
                   [(session/put-content session
                                         "application/pdf"
                                         stream)]}})))))


(def concatenate
  "Concatenate several PDF documents together.

  Parameters:
    `inputs`: A list of URIs to PDF documents."
  (reify IAction
    (perform [this state session {:keys [inputs]}]
      (d/chain (fetch-multiple-contents inputs)
               pdf/concatenate
               (fn [stream]
                 {:links
                  {:result
                   [(session/put-content session
                                         "application/pdf"
                                         stream)]}})))))


(def thumbnails
  "Generate JPEG thumbnails for a PDF document.

  Parameters:
    `input`: URI to a PDF document.
    `breadth`: Widest part of the thumbnail in pixels, the shorter end will be
        scaled accordingly."
  (reify IAction
    (perform [this state session {:keys [input breadth]}]
      (d/chain (fetch-content input)
               (partial pdf/thumbnails breadth "jpg")
               (fn [streams]
                 {:links
                  {:results (put-multiple-contents session
                                                   "image/jpeg"
                                                   streams)}})))))


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
               (fn [streams]
                 {:links
                  {:results (put-multiple-contents session
                                                   "application/pdf"
                                                   streams)}})))))


(def metadata
  "Retrieve metadata from a PDF document.

  Parameters:
    `input`: URI to a PDF document."
  (reify IAction
    (perform [this state session {:keys [input]}]
      (d/chain (fetch-content input)
               pdf/metadata
               (fn [result]
                 {:body result})))))
