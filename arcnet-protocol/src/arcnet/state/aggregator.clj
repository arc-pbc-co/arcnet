(ns arcnet.state.aggregator
  "Regional summary aggregation for ARCNet.

   Computes regional summaries every 10 seconds:
   - Total available GPUs
   - Average battery level
   - Count of solar-powered nodes

   Publishes summaries to arc.telemetry.regional-summary for
   the central tier to consume."
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [<! >! go go-loop chan close!
                                                   timeout alts!]]
            [arcnet.state.queries :as queries]
            [arcnet.state.regional :as regional]
            [arcnet.schema.registry :as schema]
            [arcnet.transport.kafka :as kafka]
            [arcnet.observability.metrics :as metrics]
            [arcnet.observability.tracing :as tracing])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const aggregation-interval-ms 10000)
(def ^:const summary-topic "arc.telemetry.regional-summary")

;; =============================================================================
;; Regional Summary Schema
;; =============================================================================

;; Note: This schema should be added to the registry, but we define it here
;; for the aggregator's use

(def regional-summary-schema
  "Schema for regional summary messages."
  [:map {:closed true}
   [:schema/version [:= 1]]
   [:summary/id :uuid]
   [:summary/timestamp :inst]
   [:summary/geozone-id :string]
   [:summary/total-nodes :int]
   [:summary/active-nodes :int]
   [:summary/available-gpus :int]
   [:summary/avg-battery-level :double]
   [:summary/avg-gpu-utilization :double]
   [:summary/solar-node-count :int]
   [:summary/grid-node-count :int]
   [:summary/battery-node-count :int]
   [:summary/total-gpu-memory-free-gb :double]])

;; =============================================================================
;; Aggregation State
;; =============================================================================

(defonce ^:private aggregator-running (atom false))
(defonce ^:private aggregator-channel (atom nil))
(defonce ^:private producer-instance (atom nil))

;; =============================================================================
;; Summary Computation
;; =============================================================================

(defn compute-regional-summary
  "Computes a regional summary from the current XTDB state.

   Parameters:
   - geozone-id: The geozone identifier for this aggregator

   Returns a summary map ready for publishing."
  [geozone-id]
  (tracing/with-span {:name "compute-regional-summary"
                      :attributes {:geozone geozone-id}}
    (let [timer (metrics/start-timer)
          ;; Query aggregations
          battery-stats (queries/aggregate-battery-levels :geozone geozone-id)
          gpu-stats (queries/aggregate-gpu-utilization :geozone geozone-id)
          energy-counts (queries/count-by-energy-source :geozone geozone-id)
          ;; Compute GPU memory
          gpu-memory-sql "SELECT SUM(node_gpu_memory_free_gb) as total
                          FROM nodes
                          WHERE node_last_seen >= ?
                            AND node_geohash LIKE ?"
          ;; Build summary
          summary {:schema/version 1
                   :summary/id (UUID/randomUUID)
                   :summary/timestamp (java.util.Date.)
                   :summary/geozone-id geozone-id
                   :summary/total-nodes (or (:count battery-stats) 0)
                   :summary/active-nodes (or (:count gpu-stats) 0)
                   :summary/available-gpus (or (:available_count gpu-stats) 0)
                   :summary/avg-battery-level (or (:avg battery-stats) 0.0)
                   :summary/avg-gpu-utilization (or (:avg gpu-stats) 0.0)
                   :summary/solar-node-count (or (:solar energy-counts) 0)
                   :summary/grid-node-count (or (:grid energy-counts) 0)
                   :summary/battery-node-count (or (:battery energy-counts) 0)
                   :summary/total-gpu-memory-free-gb 0.0}]  ; TODO: compute from query
      (metrics/record-operation!
       {:operation "compute-regional-summary"
        :duration-ms (timer)
        :success? true})
      (log/debug "Computed regional summary" {:geozone geozone-id
                                               :active-nodes (:summary/active-nodes summary)
                                               :available-gpus (:summary/available-gpus summary)})
      summary)))

;; =============================================================================
;; Summary Publishing
;; =============================================================================

(defn publish-summary!
  "Publishes a regional summary to Kafka.

   Uses the transport layer's producer with schema validation."
  [producer summary]
  (tracing/with-span {:name "publish-regional-summary"
                      :attributes {:geozone (:summary/geozone-id summary)}}
    (try
      ;; Since RegionalSummary isn't in the schema registry yet,
      ;; we'll publish as raw Transit for now
      (let [timer (metrics/start-timer)
            key-bytes (some-> (:summary/id summary) str
                              arcnet.transport.serialization/string->bytes)
            value-bytes (arcnet.transport.serialization/serialize summary)
            headers (kafka/make-headers
                     {"arcnet-schema-version" 1
                      "arcnet-entity-type" "RegionalSummary"
                      "arcnet-geozone" (:summary/geozone-id summary)})]
        (kafka/send-raw! producer summary-topic key-bytes value-bytes headers)
        (metrics/record-kafka-produce!
         {:topic summary-topic
          :schema "RegionalSummary"
          :duration-ms (timer)
          :success? true})
        (log/debug "Published regional summary"
                   {:geozone (:summary/geozone-id summary)
                    :active-nodes (:summary/active-nodes summary)}))
      (catch Exception e
        (log/error e "Failed to publish regional summary"
                   {:geozone (:summary/geozone-id summary)})
        (throw e)))))

;; =============================================================================
;; Aggregation Loop
;; =============================================================================

(defn- aggregation-loop
  "Main aggregation loop that runs every 10 seconds."
  [geozone-id stop-ch]
  (go-loop [iteration 0]
    (let [[_ ch] (alts! [stop-ch (timeout aggregation-interval-ms)])]
      (if (= ch stop-ch)
        ;; Stop signal received
        (log/info "Aggregation loop stopped" {:iterations iteration})
        ;; Normal tick
        (do
          (try
            (when (regional/healthy?)
              ;; Compute and publish summary
              (let [summary (compute-regional-summary geozone-id)]
                (when-let [producer @producer-instance]
                  (publish-summary! producer summary))
                ;; Update metrics
                (queries/update-doc-count-metrics!)))
            (catch Exception e
              (log/error e "Error in aggregation loop" {:iteration iteration})))
          (recur (inc iteration)))))))

;; =============================================================================
;; Lifecycle Management
;; =============================================================================

(defn start!
  "Starts the regional aggregator.

   Options:
   - :geozone-id - The geozone identifier (required)
   - :kafka-config - Kafka producer configuration (required)
   - :interval-ms - Aggregation interval (default 10000)

   Returns a channel that can be closed to stop the aggregator."
  [{:keys [geozone-id kafka-config interval-ms]
    :or {interval-ms aggregation-interval-ms}}]
  {:pre [(string? geozone-id)
         (map? kafka-config)]}
  (when @aggregator-running
    (log/warn "Aggregator already running")
    (throw (ex-info "Regional aggregator already running"
                    {:type :aggregator-already-running})))
  (log/info "Starting regional aggregator"
            {:geozone geozone-id
             :interval-ms interval-ms})
  ;; Create Kafka producer
  (let [producer (kafka/create-producer
                  (assoc kafka-config
                         :client-id (str "aggregator-" geozone-id)))
        stop-ch (chan)]
    (reset! producer-instance producer)
    (reset! aggregator-running true)
    (reset! aggregator-channel stop-ch)
    ;; Start aggregation loop
    (aggregation-loop geozone-id stop-ch)
    (log/info "Regional aggregator started")
    stop-ch))

(defn stop!
  "Stops the regional aggregator."
  []
  (log/info "Stopping regional aggregator")
  ;; Stop the loop
  (when-let [ch @aggregator-channel]
    (close! ch)
    (reset! aggregator-channel nil))
  ;; Close the producer
  (when-let [producer @producer-instance]
    (kafka/close! producer)
    (reset! producer-instance nil))
  (reset! aggregator-running false)
  (log/info "Regional aggregator stopped"))

;; =============================================================================
;; Status
;; =============================================================================

(defn status
  "Returns the current status of the aggregator."
  []
  {:running @aggregator-running
   :producer-active (some? @producer-instance)})

;; =============================================================================
;; Manual Trigger (for testing)
;; =============================================================================

(defn trigger-aggregation!
  "Manually triggers a single aggregation cycle.

   Useful for testing without starting the full loop."
  [geozone-id]
  (when-not (regional/healthy?)
    (throw (ex-info "Regional state not healthy" {:type :unhealthy})))
  (let [summary (compute-regional-summary geozone-id)]
    (when-let [producer @producer-instance]
      (publish-summary! producer summary))
    summary))

;; =============================================================================
;; Summary Query (for central tier)
;; =============================================================================

(defn get-latest-summary
  "Returns the most recently computed summary (cached).

   Note: For real-time data, consume from the summary topic."
  []
  ;; This would typically cache the last computed summary
  ;; For now, return nil as we don't maintain local cache
  nil)
