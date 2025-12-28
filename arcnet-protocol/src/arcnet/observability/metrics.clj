(ns arcnet.observability.metrics
  "Prometheus metrics for ARCNet observability.

   Exposes metrics via HTTP server at /metrics endpoint.

   Metric naming follows Prometheus conventions:
   - snake_case names
   - _total suffix for counters
   - _seconds suffix for durations
   - Unit in name, not label"
  (:require [clojure.tools.logging :as log])
  (:import [io.prometheus.client CollectorRegistry Counter Gauge Histogram Summary]
           [io.prometheus.client.exporter HTTPServer]
           [io.prometheus.client.hotspot DefaultExports]
           [java.net InetSocketAddress]))

;; =============================================================================
;; Registry
;; =============================================================================

(defonce ^:private default-registry (CollectorRegistry/defaultRegistry))

(defn get-registry
  "Returns the default Prometheus registry."
  []
  default-registry)

;; =============================================================================
;; Histogram Buckets
;; =============================================================================

(def ^:private latency-buckets
  "Standard latency buckets for operations (in seconds)."
  (double-array [0.001 0.005 0.01 0.025 0.05 0.1 0.25 0.5 1.0 2.5 5.0 10.0]))

(def ^:private large-latency-buckets
  "Latency buckets for longer operations like XTDB queries."
  (double-array [0.01 0.05 0.1 0.25 0.5 1.0 2.5 5.0 10.0 30.0 60.0]))

;; =============================================================================
;; Kafka Metrics
;; =============================================================================

(defonce kafka-produce-latency
  (-> (Histogram/build)
      (.name "kafka_produce_latency_seconds")
      (.help "Time to produce a message to Kafka")
      (.labelNames (into-array String ["topic" "status"]))
      (.buckets latency-buckets)
      (.register default-registry)))

(defonce kafka-consume-latency
  (-> (Histogram/build)
      (.name "kafka_consume_latency_seconds")
      (.help "Time to consume and process a message from Kafka")
      (.labelNames (into-array String ["topic" "status"]))
      (.buckets latency-buckets)
      (.register default-registry)))

(defonce messages-produced-total
  (-> (Counter/build)
      (.name "messages_produced_total")
      (.help "Total messages produced to Kafka")
      (.labelNames (into-array String ["topic" "schema" "status"]))
      (.register default-registry)))

(defonce messages-consumed-total
  (-> (Counter/build)
      (.name "messages_consumed_total")
      (.help "Total messages consumed from Kafka")
      (.labelNames (into-array String ["topic" "schema" "status"]))
      (.register default-registry)))

(defonce validation-failures-total
  (-> (Counter/build)
      (.name "validation_failures_total")
      (.help "Total schema validation failures")
      (.labelNames (into-array String ["schema" "direction"]))
      (.register default-registry)))

(defonce kafka-consumer-lag
  (-> (Gauge/build)
      (.name "kafka_consumer_lag")
      (.help "Current consumer lag per partition")
      (.labelNames (into-array String ["topic" "partition" "group_id"]))
      (.register default-registry)))

;; =============================================================================
;; XTDB Metrics
;; =============================================================================

(defonce xtdb-query-latency
  (-> (Histogram/build)
      (.name "xtdb_query_latency_seconds")
      (.help "Time to execute XTDB queries")
      (.labelNames (into-array String ["query_type" "status"]))
      (.buckets large-latency-buckets)
      (.register default-registry)))

(defonce xtdb-doc-count
  (-> (Gauge/build)
      (.name "xtdb_doc_count")
      (.help "Number of documents in XTDB")
      (.labelNames (into-array String ["doc_type"]))
      (.register default-registry)))

;; =============================================================================
;; Generic Operation Metrics
;; =============================================================================

(defonce operation-latency
  (-> (Histogram/build)
      (.name "operation_latency_seconds")
      (.help "Latency of generic operations")
      (.labelNames (into-array String ["operation" "status"]))
      (.buckets latency-buckets)
      (.register default-registry)))

(defonce operation-total
  (-> (Counter/build)
      (.name "operation_total")
      (.help "Total operations executed")
      (.labelNames (into-array String ["operation" "status"]))
      (.register default-registry)))

;; =============================================================================
;; Metric Recording Functions
;; =============================================================================

(defn record-kafka-produce!
  "Records a Kafka produce operation."
  [{:keys [topic schema duration-ms success?]}]
  (let [status (if success? "success" "failure")
        duration-sec (/ duration-ms 1000.0)]
    (-> kafka-produce-latency
        (.labels (into-array String [topic status]))
        (.observe duration-sec))
    (-> messages-produced-total
        (.labels (into-array String [topic (or schema "unknown") status]))
        (.inc))))

(defn record-kafka-consume!
  "Records a Kafka consume operation."
  [{:keys [topic schema duration-ms success?]}]
  (let [status (if success? "success" "failure")
        duration-sec (/ duration-ms 1000.0)]
    (-> kafka-consume-latency
        (.labels (into-array String [topic status]))
        (.observe duration-sec))
    (-> messages-consumed-total
        (.labels (into-array String [topic (or schema "unknown") status]))
        (.inc))))

(defn record-validation-failure!
  "Records a schema validation failure."
  [{:keys [schema direction]}]
  (-> validation-failures-total
      (.labels (into-array String [(or schema "unknown") (name direction)]))
      (.inc)))

(defn set-consumer-lag!
  "Sets the current consumer lag for a partition."
  [{:keys [topic partition group-id lag]}]
  (-> kafka-consumer-lag
      (.labels (into-array String [topic (str partition) group-id]))
      (.set (double lag))))

(defn record-xtdb-query!
  "Records an XTDB query operation."
  [{:keys [query-type duration-ms success?]}]
  (let [status (if success? "success" "failure")
        duration-sec (/ duration-ms 1000.0)]
    (-> xtdb-query-latency
        (.labels (into-array String [query-type status]))
        (.observe duration-sec))))

(defn set-xtdb-doc-count!
  "Sets the document count for a type in XTDB."
  [{:keys [doc-type count]}]
  (-> xtdb-doc-count
      (.labels (into-array String [doc-type]))
      (.set (double count))))

(defn record-operation!
  "Records a generic operation."
  [{:keys [operation duration-ms success?]}]
  (let [status (if success? "success" "failure")
        duration-sec (/ duration-ms 1000.0)]
    (-> operation-latency
        (.labels (into-array String [operation status]))
        (.observe duration-sec))
    (-> operation-total
        (.labels (into-array String [operation status]))
        (.inc))))

;; =============================================================================
;; Timer Helper
;; =============================================================================

(defn start-timer
  "Returns a function that, when called, returns elapsed milliseconds."
  []
  (let [start (System/nanoTime)]
    (fn []
      (/ (- (System/nanoTime) start) 1000000.0))))

;; =============================================================================
;; HTTP Server
;; =============================================================================

(defonce ^:private http-server (atom nil))

(defn start-server!
  "Starts the Prometheus HTTP server on the specified port.
   Default port is 9090."
  ([] (start-server! 9090))
  ([port]
   (when @http-server
     (log/warn "Metrics server already running, stopping first")
     (stop-server!))
   ;; Register JVM hotspot metrics
   (DefaultExports/initialize)
   (let [addr (InetSocketAddress. port)
         server (HTTPServer. addr default-registry)]
     (reset! http-server server)
     (log/info "Prometheus metrics server started" {:port port :path "/metrics"})
     server)))

(defn stop-server!
  "Stops the Prometheus HTTP server."
  []
  (when-let [server @http-server]
    (.stop server)
    (reset! http-server nil)
    (log/info "Prometheus metrics server stopped")))

(defn server-running?
  "Returns true if the metrics server is running."
  []
  (some? @http-server))

;; =============================================================================
;; with-metrics Macro
;; =============================================================================

(defmacro with-timing
  "Executes body and returns [result duration-ms]."
  [& body]
  `(let [start# (System/nanoTime)
         result# (do ~@body)
         duration# (/ (- (System/nanoTime) start#) 1000000.0)]
     [result# duration#]))

(defmacro with-metrics
  "Executes body and records metrics for the operation.

   Options:
   - :operation - Operation name for generic metrics (required)
   - :on-success - Function called with {:result r :duration-ms d} on success
   - :on-failure - Function called with {:error e :duration-ms d} on failure

   Returns the result of body, or re-throws any exception after recording.

   Example:
   (with-metrics {:operation \"fetch-user\"}
     (db/get-user id))"
  [{:keys [operation on-success on-failure] :as opts} & body]
  `(let [op-name# ~operation
         timer# (start-timer)]
     (try
       (let [result# (do ~@body)
             duration# (timer#)]
         (record-operation! {:operation op-name#
                             :duration-ms duration#
                             :success? true})
         (when ~on-success
           (~on-success {:result result# :duration-ms duration#}))
         result#)
       (catch Throwable t#
         (let [duration# (timer#)]
           (record-operation! {:operation op-name#
                               :duration-ms duration#
                               :success? false})
           (when ~on-failure
             (~on-failure {:error t# :duration-ms duration#}))
           (throw t#))))))

(defmacro with-kafka-produce-metrics
  "Records Kafka produce metrics for the body.

   Example:
   (with-kafka-produce-metrics {:topic \"events\" :schema \"Event\"}
     (.send producer record))"
  [{:keys [topic schema]} & body]
  `(let [timer# (start-timer)]
     (try
       (let [result# (do ~@body)
             duration# (timer#)]
         (record-kafka-produce! {:topic ~topic
                                 :schema ~schema
                                 :duration-ms duration#
                                 :success? true})
         result#)
       (catch Throwable t#
         (let [duration# (timer#)]
           (record-kafka-produce! {:topic ~topic
                                   :schema ~schema
                                   :duration-ms duration#
                                   :success? false})
           (throw t#))))))

(defmacro with-kafka-consume-metrics
  "Records Kafka consume metrics for the body.

   Example:
   (with-kafka-consume-metrics {:topic \"events\" :schema \"Event\"}
     (process-message msg))"
  [{:keys [topic schema]} & body]
  `(let [timer# (start-timer)]
     (try
       (let [result# (do ~@body)
             duration# (timer#)]
         (record-kafka-consume! {:topic ~topic
                                 :schema ~schema
                                 :duration-ms duration#
                                 :success? true})
         result#)
       (catch Throwable t#
         (let [duration# (timer#)]
           (record-kafka-consume! {:topic ~topic
                                   :schema ~schema
                                   :duration-ms duration#
                                   :success? false})
           (throw t#))))))

(defmacro with-xtdb-metrics
  "Records XTDB query metrics for the body.

   Example:
   (with-xtdb-metrics {:query-type \"entity-lookup\"}
     (xt/entity db eid))"
  [{:keys [query-type]} & body]
  `(let [timer# (start-timer)]
     (try
       (let [result# (do ~@body)
             duration# (timer#)]
         (record-xtdb-query! {:query-type ~query-type
                              :duration-ms duration#
                              :success? true})
         result#)
       (catch Throwable t#
         (let [duration# (timer#)]
           (record-xtdb-query! {:query-type ~query-type
                                :duration-ms duration#
                                :success? false})
           (throw t#))))))

;; =============================================================================
;; Metrics Summary for Testing
;; =============================================================================

(defn get-metric-value
  "Gets the current value of a metric (for testing).
   Returns nil if metric not found."
  [metric-name labels]
  (try
    (let [samples (.collect (.get default-registry metric-name))]
      (when (seq samples)
        (let [sample (first samples)]
          (.value (first (.samples sample))))))
    (catch Exception _ nil)))
