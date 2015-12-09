(ns documint.util-test
  "Tests for `document.util`."
  (:require [documint.util :as util]
            [clojure.test :refer [deftest testing is]]))


(deftest transform-map
  "Tests for `documint.util/transform-map."
  (testing "empty"
    (is (= (util/transform-map inc {})
           {})))

  (testing "primitive"
    (is (= (util/transform-map inc {:a 1 :b 2})
           {:a 2 :b 3})))

  (testing "nested vector"
    (is (= (util/transform-map inc {:a [1 2 3]})
           {:a [2 3 4]})))

  (testing "nested map"
    (is (= (util/transform-map inc {:a {:b 1}})
           {:a {:b 2}}))))


(deftest piped-input-stream
  "Tests for `documint.util/piped-input-stream."
  (testing "success"
    (let [f (util/piped-input-stream
             (fn [output]
               (spit output "hello")))]
      (is (= (slurp @f)
             "hello"))))

  (testing "failure"
    (let [f (util/piped-input-stream
             (fn [output]
               (throw (ex-info "Bad stuff" {:cause :bad-stuff}))))]
      (is (thrown? clojure.lang.ExceptionInfo @f))
      (try
        @f
        (catch clojure.lang.ExceptionInfo e
          (is (= (ex-data e)
                 {:cause :bad-stuff})))))))
