(ns documint.metrics
   "Documint metrics registry"
   (:require [iapetos.core :as prometheus]
             [iapetos.collector.ring :as ring]
             [iapetos.collector.jvm :as jvm]
             [manifold.deferred :as d]))


(defn- tapf
  "Make a function pass its parameter through"
  [f & rest]
  (fn [result]
    (apply f rest)
    result))


(defn- const
  "Make a function discard its arguments"
  [f & rest]
  (fn [& _]
    (apply f rest)))


(defn- pass
  "Do nothing"
  [& _])


(defn- bracket
  "Bracket an async operation in some other calls"
  ([before success failure always d]
   (let [result (before)]
     (d/finally
       (d/on-realized d
        (tapf success result)
        (tapf failure result))
       (partial always result))))
  ([success failure always d]
   (bracket pass (const success) (const failure) (const always) d))
  ([before always d]
   (bracket before pass pass always d))
  ([always d]
   (bracket pass pass pass (const always) d)))


(defn async-duration
  "Wrap the given async operation to write its execution time to the given
   collector.
  
   Works with [[gauge]], [[histogram]] and [[summary]] collectors."
  [collector d]
  (bracket (partial prometheus/start-timer collector) #(%) d))


(defn async-activity-counter
  "Wrap the given async operation to increment the given collector once it is
   entered and decrement it once execution is done. This needs a [[gauge]]
   collector (since [[counter]] ones cannot be decremented).

   Example: Counting the number of in-flight requests in an HTTP server."
  [collector d]
  (bracket (partial prometheus/inc collector)
           (const prometheus/dec collector)
           d))


(defn async-counters
  "Wrap the given async operation to increment the given counters:

   - `:total`: incremented when the block is left,
   - `:success`: incremented when the block has executed successfully,
   - `:failure`: incremented when the block has thrown an exception."
  [{:keys [total success failure]} d]
  (bracket (partial prometheus/inc success)
           (partial prometheus/inc failure)
           (partial prometheus/inc total)
           d))


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
       (prometheus/counter
        :documint/actions-errored-total
        {:description "Total actions errored"
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
