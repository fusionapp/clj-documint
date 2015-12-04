(ns documint.session-test
  "Tests for `documint.session`."
  (:require [documint.session :as session]
            [clojure.test :refer [deftest testing is]]))


(defn counter
  "An identity generator that uses ever increasing numbers.

  Use in place of UUIDs when testing."
  [n]
  (let [counter (atom n)]
    (fn []
      (swap! counter inc)
      (str @counter))))


(deftest temp-file-session-factory
  (testing "new-session"
    (let [session-factory (session/temp-file-session-factory (counter 1000))
          session         (session/new-session session-factory)]
      (is (= "1001"
             (:id session)))
      (is (= session
             (session/get-session session-factory (:id session))))))

  (testing "get-content / put-content"
    (let [session-factory (session/temp-file-session-factory (counter 1000))
          session         (session/new-session session-factory)
          entry           (session/put-content session "text/plain" "hello")
          content         (session/get-content session (:id entry))]
      (is (= "text/plain"
             (:content-type entry)))
      (is (= "text/plain"
             (:content-type content)))
      (is (= "hello"
             (slurp (:stream content))))))

  (testing "destroy"
    (let [session-factory (session/temp-file-session-factory (counter 1000))
          session         (session/new-session session-factory)
          entry           (session/put-content session "text/plain" "hello")]
      (is (= "1002"
             (:id entry)))
      (is (= true
             (.exists (:file entry))))
      (session/destroy session)
      (is (= false
             (.exists (:file entry))))
      (is (= nil
             (session/get-session session-factory (:id session))))
      (is (= nil
             (session/get-content session (:id entry)))))))
