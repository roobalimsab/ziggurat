(ns ziggurat.metrics
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :refer [stringify-keys]]
            [ziggurat.config :refer [ziggurat-config]]
            [ziggurat.util.java-util :as util])
  (:import com.gojek.metrics.datadog.DatadogReporter
           [com.gojek.metrics.datadog.transport UdpTransport UdpTransport$Builder]
           [io.dropwizard.metrics5 Histogram Meter MetricName MetricRegistry]
           java.util.concurrent.TimeUnit)
  (:gen-class
   :name tech.gojek.ziggurat.internal.Metrics
   :methods [^{:static true} [incrementCount [String String] void]
             ^{:static true} [incrementCount [String String java.util.Map] void]
             ^{:static true} [decrementCount [String String] void]
             ^{:static true} [decrementCount [String String java.util.Map] void]
             ^{:static true} [reportTime     [String long] void]
             ^{:static true} [reportTime     [String long java.util.Map] void]]))

(defonce metrics-registry
  (MetricRegistry.))

(defn- merge-tags
  [additional-tags]
  (let [default-tags {"actor" (:app-name (ziggurat-config))}]
    (merge default-tags (stringify-keys additional-tags))))

(defn- get-tagged-metric
  [metric-name tags]
  (.tagged ^MetricName metric-name tags))

(defn mk-meter
  ([category metric]
   (mk-meter category metric nil))
  ([category metric additional-tags]
   (let [namespace     (str category "." metric)
         metric-name   (MetricRegistry/name ^String namespace nil)
         tags          (merge-tags additional-tags)
         tagged-metric (get-tagged-metric metric-name tags)]
     (.meter ^MetricRegistry metrics-registry ^MetricName tagged-metric))))

(defn mk-histogram
  ([category metric]
   (mk-histogram category metric nil))
  ([category metric additional-tags]
   (let [namespace     (str category "." metric)
         metric-name   (MetricRegistry/name ^String namespace nil)
         tags          (merge-tags additional-tags)
         tagged-metric (.tagged ^MetricName metric-name tags)]
     (.histogram ^MetricRegistry metrics-registry ^MetricName tagged-metric))))

(defn intercalate-dot
  [names]
  (str/join "." names))

(defn- get-metric-namespaces
  [metric-namespaces]
  (if (vector? metric-namespaces)
    (intercalate-dot metric-namespaces)
    metric-namespaces))

(defn- get-v
  [f d v]
  (if (f v) v d))

(def ^:private get-int (partial get-v number? 1))

(def ^:private get-map (partial get-v map? {}))

(defn- inc-or-dec-count
  ([sign metric-namespace metric]
   (inc-or-dec-count sign metric-namespace metric 1 {}))
  ([sign metric-namespace metric n-or-additional-tags]
   (inc-or-dec-count sign metric-namespace metric (get-int n-or-additional-tags) (get-map n-or-additional-tags)))
  ([sign metric-namespace metric n additional-tags]
   (inc-or-dec-count sign {:metric-namespace metric-namespace :metric metric :n n :additional-tags additional-tags}))
  ([sign {:keys [metric-namespace metric n additional-tags]}]
   (let [metric-ns (get-metric-namespaces metric-namespace)
         meter     ^Meter (mk-meter metric-ns metric (get-map additional-tags))]
     (.mark meter (sign (get-int n))))))

(def increment-count (partial inc-or-dec-count +))

(def decrement-count (partial inc-or-dec-count -))

(defn report-time
  ([metric-namespaces time-val]
   (report-time metric-namespaces time-val nil))
  ([metric-namespaces time-val additional-tags]
   (let [metric-namespace (get-metric-namespaces metric-namespaces)
         histogram        ^Histogram (mk-histogram metric-namespace "all" additional-tags)]
     (.update histogram (int time-val)))))

(defn start-statsd-reporter [statsd-config env]
  (let [{:keys [enabled host port]} statsd-config]
    (when enabled
      (let [transport (-> (UdpTransport$Builder.)
                          (.withStatsdHost host)
                          (.withPort port)
                          (.build))

            reporter (-> (DatadogReporter/forRegistry metrics-registry)
                         (.withTransport transport)
                         (.withTags [(str env)])
                         (.build))]
        (log/info "Starting statsd reporter")
        (.start reporter 1 TimeUnit/SECONDS)
        {:reporter reporter :transport transport}))))

(defn stop-statsd-reporter [datadog-reporter]
  (when-let [{:keys [reporter transport]} datadog-reporter]
    (.stop ^DatadogReporter reporter)
    (.close ^UdpTransport transport)
    (log/info "Stopped statsd reporter")))

(defn -incrementCount
  ([metric-namespace metric]
   (increment-count metric-namespace metric))
  ([metric-namespace metric additional-tags]
   (increment-count metric-namespace metric (util/java-map->clojure-map additional-tags))))

(defn -decrementCount
  ([metric-namespace metric]
   (decrement-count metric-namespace metric))
  ([metric-namespace metric additional-tags]
   (decrement-count metric-namespace metric (util/java-map->clojure-map additional-tags))))

(defn -reportTime
  ([metric-namespace time-val]
   (report-time metric-namespace time-val))
  ([metric-namespace time-val additional-tags]
   (report-time metric-namespace time-val (util/java-map->clojure-map additional-tags))))