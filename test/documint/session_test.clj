(ns documint.session-test
  "Tests for `documint.session`."
  (:require [documint.session :as session]
            [documint.util :refer [counter]]
            [manifold.deferred :as d]
            [manifold.time :refer [mock-clock with-clock minutes advance]]
            [clojure.test :refer [deftest testing is]]))


(deftest temp-file-session-factory
  (testing "new-session"
    (let [session-factory (session/temp-file-session-factory (counter 1000))
          session         (session/new-session session-factory)]
      (is (= "1001"
             (:id session)))
      (is (= session
             (session/get-session session-factory (:id session))))))

  (testing "allocate-thunk"
    (let [session-factory (session/temp-file-session-factory (counter 1000))
          session         (session/new-session session-factory)
          entry           (session/allocate-thunk session nil)]
      (is (= "1002"
             (:id entry)))
      (is (= false
             (d/realized? (:deferred-result entry))))))

  (testing "get-content / put-content"
    (let [session-factory (session/temp-file-session-factory (counter 1000))
          session         (session/new-session session-factory)
          entry           (session/put-content session "text/plain" "hello")
          content         @(session/get-content session (:id entry))]
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
             (session/get-content session (:id entry))))
      ; Destroy is idempotent.
      (session/destroy session)))

  (testing "timeout"
    (let [clock (mock-clock)]
      (with-clock clock
        (let [session-factory (session/temp-file-session-factory (counter 1000)
                                                                 (minutes 5))
          session         (session/new-session session-factory)
          entry           (session/put-content session "text/plain" "hello")]
          (is (= "1002"
                 (:id entry)))
          (is (= true
                 (.exists (:file entry))))
          (advance clock (minutes 4))
          (is (= true
                 (.exists (:file entry))))
          (advance clock (minutes 1))
          (is (= false
                 (.exists (:file entry))))
          (is (= nil
                 (session/get-session session-factory (:id session))))
          (is (= nil
                 (session/get-content session (:id entry)))))))))
