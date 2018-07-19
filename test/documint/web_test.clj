(ns documint.web-test
  "Tests for `document.web`."
  (:require [clojure.test :refer [deftest testing are is]]
            [manifold.deferred :as d]
            [documint.util :refer [counter]]
            [documint.content :as content]
            [documint.session :as session]
            [documint.web :as web]))


(deftest local-uri?
  (testing "default port"
    (let [local-uri? (web/local-uri? {:request {:scheme      :http
                                                :server-name "example.com"
                                                :server-port 80}})]
      (testing "non-local"
        (are [x] (nil? (local-uri? x))
          "http://example.com:3000/path"
          "https://example.com/path"
          "http://other.example.com/path"))

      (testing "local"
        (are [x y] (= (local-uri? x) y)
          "http://example.com"      ""
          "http://example.com/"     "/"
          "http://example.com/path" "/path"))))

  (testing "non-default port"
    (let [local-uri? (web/local-uri? {:request {:scheme      :http
                                                :server-name "example.com"
                                                :server-port 3000}})]
      (testing "non-local"
        (are [x] (nil? (local-uri? x))
          "http://example.com/path"
          "https://example.com:3000/path"
          "http://example.com:6000/path"
          "http://other.example.com:3000/path"))

      (testing "local"
        (are [x y] (= (local-uri? x) y)
          "http://example.com:3000"      ""
          "http://example.com:3000/"     "/"
          "http://example.com:3000/path" "/path")))))


(deftest local-fetcher
  (let [session-factory (session/temp-file-session-factory (counter 1000))]
    (testing "exists"
      (let [session (session/new-session session-factory)
            entry   (session/put-content session "text/plain" "hello")
            loc     [(content/session-id entry)
                     (content/content-id entry)]
            content @((web/local-fetcher session-factory) loc)]
        (is (= "text/plain"
               (:content-type content)))
        (is (= "hello"
               (slurp (:stream content))))))

    (testing "nonexistent session"
      (is (nil? ((web/local-fetcher session-factory) ["1" "2"]))))

    (testing "nonexistent content"
      (let [session (session/new-session session-factory)
            entry   (session/put-content session "text/plain" "hello")
            loc     [(content/session-id entry) "nope"]]
        (is (nil? ((web/local-fetcher session-factory) loc)))))))


(deftest content-getter
  (let [content-1 "http://example.com/sessions/1/contents/2"
        content-2 "http://example.com/sessions/3/contents/4"]
    (testing "non-local"
      (let [near?       (constantly nil)
            fetch-near  #(throw (ex-info "Unexpected near fetch" {:loc %}))
            fetch-far   d/success-deferred
            get-content (web/content-getter near? fetch-near fetch-far)]
        (testing "single"
          (is (= content-1
                 @(get-content content-1))))

        (testing "multiple"
          (is (= [content-1 content-2]
                 @(get-content [content-1 content-2]))))))

    (testing "local"
      (let [near?       (fn [uri] (.getPath (java.net.URI. uri)))
            fetch-near  d/success-deferred
            fetch-far   #(throw (ex-info "Unexpected far fetch" {:uri %}))
            get-content (web/content-getter near? fetch-near fetch-far)]
          (testing "single"
            (is (= ["1" "2"]
                   @(get-content content-1))))

          (testing "multiple"
            (is (= [["1" "2"] ["3" "4"]]
                   @(get-content [content-1 content-2]))))))))
