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
