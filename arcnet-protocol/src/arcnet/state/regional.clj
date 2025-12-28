(ns arcnet.state.regional
  "Regional state management with embedded XTDB v2.

   This namespace manages the regional tier of ARCNet's state architecture.
   Each geozone aggregator runs an embedded XTDB node that:
   - Ingests telemetry from the regional Kafka partition
   - Provides fast local queries for node availability
   - Maintains bitemporal history for analysis

   Architecture:
   - Embedded XTDB v2 node (not remote connection)
   - Kafka consumer for telemetry ingestion
   - 30-second staleness threshold for node liveness"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [<! >! go go-loop chan close!]]
            [arcnet.schema.registry :as schema]
            [arcnet.transport.kafka :as kafka]
            [arcnet.observability.metrics :as metrics]
            [arcnet.observability.tracing :as tracing])
  (:import [java.time Instant Duration]
           [java.util UUID Date]
           [xtdb.api Xtdb IXtdb]
           [xtdb.api.tx TxOps]
           [xtdb.api.query IKeyFn]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const staleness-threshold-seconds 30)
(def ^:const telemetry-topic "arc.telemetry.nodes")

;; =============================================================================
;; XTDB Node Management
;; =============================================================================

(defonce ^:private xtdb-node (atom nil))
(defonce ^:private consumer-running (atom false))
(defonce ^:private consumer-channel (atom nil))

(defn start-xtdb!
  "Starts an embedded XTDB v2 node.

   Options:
   - :data-dir - Directory for persistent storage (default: in-memory)
   - :geozone-id - Geozone identifier for logging

   Returns the XTDB node instance."
  [{:keys [data-dir geozone-id]
    :or {geozone-id "unknown"}}]
  (when @xtdb-node
    (log/warn "XTDB node already running, stopping first")
    (stop-xtdb!))
  (log/info "Starting embedded XTDB v2 node" {:geozone geozone-id
                                               :data-dir data-dir})
  (let [node (if data-dir
               ;; Persistent storage
               (Xtdb/openNode
                (-> (Xtdb/nodeConfig)
                    (.dataDir (java.io.File. data-dir))))
               ;; In-memory for development/testing
               (Xtdb/openNode))]
    (reset! xtdb-node node)
    (log/info "XTDB v2 node started successfully")
    node))

(defn stop-xtdb!
  "Stops the embedded XTDB node."
  []
  (when-let [node @xtdb-node]
    (log/info "Stopping XTDB node")
    (.close node)
    (reset! xtdb-node nil)
    (log/info "XTDB node stopped")))

(defn get-node
  "Returns the current XTDB node, starting one if necessary."
  []
  (or @xtdb-node
      (throw (ex-info "XTDB node not initialized. Call start-xtdb! first."
                      {:type :xtdb-not-initialized}))))

;; =============================================================================
;; Document Transformation
;; =============================================================================

(defn- telemetry->doc
  "Transforms NodeTelemetry into an XTDB document.

   Adds:
   - :xt/id - Document ID (node UUID)
   - :node/geohash - For spatial filtering
   - :node/last-seen - Timestamp for staleness detection"
  [telemetry]
  (let [now (Instant/now)]
    {:xt/id (:id telemetry)
     :node/id (:id telemetry)
     :node/geohash (:geohash telemetry)
     :node/geozone-id (subs (:geohash telemetry) 0 3)  ; Coarse geozone from geohash prefix
     :node/energy-source (:energy-source telemetry)
     :node/battery-level (:battery-level telemetry)
     :node/gpu-utilization (:gpu-utilization telemetry)
     :node/gpu-memory-free-gb (:gpu-memory-free-gb telemetry)
     :node/models-loaded (:models-loaded telemetry)
     :node/last-seen now
     :node/telemetry-timestamp (:timestamp telemetry)}))

;; =============================================================================
;; Telemetry Ingestion
;; =============================================================================

(defn put-node!
  "Writes a single node document to XTDB.

   Uses XTDB v2's new transaction API."
  [node doc]
  (tracing/with-xtdb-span {:query-type "put" :query {:id (:xt/id doc)}}
    (let [timer (metrics/start-timer)]
      (try
        (.submitTx node (TxOps/put (:xt/id doc) doc))
        (metrics/record-xtdb-query!
         {:query-type "put"
          :duration-ms (timer)
          :success? true})
        (log/debug "Node document written" {:node-id (:xt/id doc)})
        true
        (catch Exception e
          (metrics/record-xtdb-query!
           {:query-type "put"
            :duration-ms (timer)
            :success? false})
          (log/error e "Failed to write node document" {:node-id (:xt/id doc)})
          (throw e))))))

(defn ingest-telemetry!
  "Ingests a single NodeTelemetry message into XTDB.

   Transforms the telemetry into a node document and performs
   an XTDB put operation.

   Returns true on success."
  [telemetry]
  {:pre [(= 2 (:schema/version telemetry))]}
  (let [node (get-node)
        doc (telemetry->doc telemetry)]
    (put-node! node doc)))

(defn ingest-batch!
  "Ingests a batch of telemetry messages efficiently.

   Uses a single transaction for the entire batch."
  [telemetry-batch]
  (when (seq telemetry-batch)
    (let [node (get-node)
          docs (map telemetry->doc telemetry-batch)
          timer (metrics/start-timer)]
      (tracing/with-xtdb-span {:query-type "batch-put" :query {:count (count docs)}}
        (try
          ;; XTDB v2 batch transaction
          (let [tx-ops (map (fn [doc] (TxOps/put (:xt/id doc) doc)) docs)]
            (.submitTx node (into-array tx-ops)))
          (metrics/record-xtdb-query!
           {:query-type "batch-put"
            :duration-ms (timer)
            :success? true})
          (log/debug "Batch ingested" {:count (count docs)})
          true
          (catch Exception e
            (metrics/record-xtdb-query!
             {:query-type "batch-put"
              :duration-ms (timer)
              :success? false})
            (log/error e "Batch ingestion failed" {:count (count docs)})
            (throw e)))))))

;; =============================================================================
;; Kafka Consumer Integration
;; =============================================================================

(defn- process-kafka-records
  "Processes a batch of Kafka records, ingesting valid telemetry."
  [records]
  (let [valid-telemetry (->> records
                             (filter #(= :valid (:status %)))
                             (filter #(= :arcnet/NodeTelemetry.v2 (:schema %)))
                             (map :data))]
    (when (seq valid-telemetry)
      (ingest-batch! valid-telemetry)
      (count valid-telemetry))))

(defn start-telemetry-consumer!
  "Starts a Kafka consumer that ingests telemetry into XTDB.

   Options:
   - :kafka-config - Kafka consumer configuration
   - :topic - Topic to consume (default: arc.telemetry.nodes)
   - :poll-timeout-ms - Poll timeout (default: 1000)
   - :batch-size - Max records per batch (default: 100)

   Returns a channel that can be closed to stop the consumer."
  [{:keys [kafka-config topic poll-timeout-ms]
    :or {topic telemetry-topic
         poll-timeout-ms 1000}}]
  (when @consumer-running
    (log/warn "Consumer already running")
    (throw (ex-info "Telemetry consumer already running"
                    {:type :consumer-already-running})))
  (log/info "Starting telemetry consumer" {:topic topic})
  (let [stop-ch (chan)
        consumer (kafka/create-consumer
                  (assoc kafka-config
                         :group-id (str (:group-id kafka-config) "-telemetry")
                         :create-dead-letter-producer? true))]
    (reset! consumer-running true)
    (reset! consumer-channel stop-ch)
    ;; Start consumer loop
    (go-loop []
      (let [[_ ch] (async/alts! [stop-ch (async/timeout poll-timeout-ms)])]
        (if (= ch stop-ch)
          ;; Stop signal received
          (do
            (log/info "Stopping telemetry consumer")
            (kafka/close! consumer)
            (reset! consumer-running false))
          ;; Normal poll
          (do
            (try
              (kafka/subscribe! consumer [topic])
              (let [records (kafka/poll! consumer poll-timeout-ms)
                    count (process-kafka-records records)]
                (when (and count (pos? count))
                  (kafka/commit! consumer)
                  (log/debug "Committed offset after ingesting" {:count count})))
              (catch Exception e
                (log/error e "Error in telemetry consumer loop")))
            (recur)))))
    stop-ch))

(defn stop-telemetry-consumer!
  "Stops the telemetry consumer."
  []
  (when-let [ch @consumer-channel]
    (close! ch)
    (reset! consumer-channel nil)))

;; =============================================================================
;; Node Status Helpers
;; =============================================================================

(defn node-stale?
  "Returns true if a node hasn't been seen within the staleness threshold."
  [last-seen]
  (when last-seen
    (let [now (Instant/now)
          threshold (Duration/ofSeconds staleness-threshold-seconds)
          last-seen-instant (if (instance? Instant last-seen)
                              last-seen
                              (.toInstant last-seen))]
      (.isAfter now (.plus last-seen-instant threshold)))))

(defn staleness-cutoff
  "Returns the Instant threshold for considering nodes stale."
  []
  (.minus (Instant/now) (Duration/ofSeconds staleness-threshold-seconds)))

;; =============================================================================
;; Lifecycle Management
;; =============================================================================

(defn start!
  "Starts the regional state tier.

   Options:
   - :xtdb-config - XTDB configuration
   - :kafka-config - Kafka consumer configuration
   - :consume-telemetry? - Whether to start the Kafka consumer (default true)"
  [{:keys [xtdb-config kafka-config consume-telemetry?]
    :or {consume-telemetry? true}}]
  (log/info "Starting regional state tier")
  ;; Start XTDB
  (start-xtdb! xtdb-config)
  ;; Start Kafka consumer if configured
  (when (and consume-telemetry? kafka-config)
    (start-telemetry-consumer! {:kafka-config kafka-config}))
  (log/info "Regional state tier started"))

(defn stop!
  "Stops the regional state tier."
  []
  (log/info "Stopping regional state tier")
  (stop-telemetry-consumer!)
  (stop-xtdb!)
  (log/info "Regional state tier stopped"))

;; =============================================================================
;; Health Check
;; =============================================================================

(defn healthy?
  "Returns true if the regional state tier is healthy."
  []
  (and (some? @xtdb-node)
       (try
         ;; Simple query to verify XTDB is responsive
         (.query (get-node) "SELECT 1")
         true
         (catch Exception _ false))))

(defn status
  "Returns the current status of the regional state tier."
  []
  {:xtdb-running (some? @xtdb-node)
   :consumer-running @consumer-running
   :healthy (healthy?)})
