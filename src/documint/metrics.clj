(ns documint.metrics
   "Documint metrics registry"
   (:require [iapetos.core :as prometheus]
             [iapetos.collector.ring :as ring]
             [iapetos.collector.jvm :as jvm]))

(defonce registry
  (-> (prometheus/collector-registry)
      (ring/initialize)
      (jvm/initialize)
      (prometheus/register
       (prometheus/counter
        :documint/actions-total
        {:description "Total actions run"
         :labels [:action]})
       (prometheus/counter
        :documint/actions-succeeded-total
        {:description "Total actions succeeded"
         :labels [:action]})
       (prometheus/counter
        :documint/actions-failed-total
        {:description "Total actions failed"
         :labels [:action]})
       (prometheus/gauge
        :documint/actions-running-total
        {:description "Total actions currently running"
         :labels [:action]})
       (prometheus/histogram
        :documint/actions-seconds
        {:description "Action runtime"
         :labels [:action]
         :buckets [0.01 0.02 0.05 0.1 0.5 1.0 5.0 7.5 10.0 12.5 15.0]})
       (prometheus/gauge
        :documint/sessions-active-total
        {:description "Total sessions currently existing"}))))
