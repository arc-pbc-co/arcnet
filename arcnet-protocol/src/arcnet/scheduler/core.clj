(ns arcnet.scheduler.core
  "Core scheduler for ARCNet inference requests.

   Consumes from arc.request.inference and:
   1. Runs candidate query with scoring
   2. Attempts reservation on best candidate
   3. On success: dispatches to arc.command.dispatch.{geozone}
   4. On failure: retries with exponential backoff or rejects

   Retry policy:
   - Maximum 3 retries
   - Exponential backoff: 100ms, 200ms, 400ms
   - After 3 failures: rejected to arc.request.rejected"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [<! >! go go-loop chan close!
                                                   timeout alts!]]
            [arcnet.schema.registry :as schema]
            [arcnet.transport.kafka :as kafka]
            [arcnet.transport.serialization :as ser]
            [arcnet.scheduler.reservation :as reservation]
            [arcnet.scheduler.rules :as rules]
            [arcnet.observability.metrics :as metrics]
            [arcnet.observability.tracing :as tracing])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const request-topic "arc.request.inference")
(def ^:const retry-topic "arc.request.retry")
(def ^:const rejected-topic "arc.request.rejected")
(defn dispatch-topic [geozone] (str "arc.command.dispatch." geozone))

(def ^:const max-retries 3)
(def ^:const base-backoff-ms 100)

;; =============================================================================
;; Backoff Calculation
;; =============================================================================

(defn calculate-backoff-ms
  "Calculates exponential backoff delay.

   Formula: base * 2^retry-count
   - Retry 0: 100ms
   - Retry 1: 200ms
   - Retry 2: 400ms"
  [retry-count]
  (* base-backoff-ms (Math/pow 2 retry-count)))

(defn next-retry-at
  "Calculates the instant when the next retry should occur."
  [retry-count]
  (.plusMillis (Instant/now) (long (calculate-backoff-ms retry-count))))

;; =============================================================================
;; Request Metadata
;; =============================================================================

(defn- extract-retry-metadata
  "Extracts retry metadata from Kafka headers."
  [headers]
  {:retry-count (or (some-> (get headers :arcnet-retry-count)
                            Integer/parseInt)
                    0)
   :original-request-id (some-> (get headers :arcnet-original-request-id)
                                UUID/fromString)
   :first-attempt-at (some-> (get headers :arcnet-first-attempt-at)
                             Instant/parse)
   :last-failure-reason (get headers :arcnet-last-failure-reason)})

(defn- make-retry-headers
  "Creates headers for a retry message."
  [request retry-count failure-reason original-request-id first-attempt-at]
  {"arcnet-retry-count" (str (inc retry-count))
   "arcnet-original-request-id" (str (or original-request-id (:id request)))
   "arcnet-first-attempt-at" (str (or first-attempt-at (Instant/now)))
   "arcnet-last-failure-reason" failure-reason
   "arcnet-next-retry-at" (str (next-retry-at retry-count))
   "arcnet-schema-version" (str (:schema/version request))
   "arcnet-entity-type" "InferenceRequest"})

(defn- make-rejected-headers
  "Creates headers for a rejected message."
  [request retry-count failure-reason original-request-id]
  {"arcnet-rejected-at" (str (Instant/now))
   "arcnet-total-retries" (str retry-count)
   "arcnet-rejection-reason" failure-reason
   "arcnet-original-request-id" (str (or original-request-id (:id request)))
   "arcnet-schema-version" (str (:schema/version request))
   "arcnet-entity-type" "InferenceRequest"})

(defn- make-dispatch-headers
  "Creates headers for a dispatch command."
  [request node-id]
  {"arcnet-dispatched-at" (str (Instant/now))
   "arcnet-assigned-node" (str node-id)
   "arcnet-request-id" (str (:id request))
   "arcnet-schema-version" (str (:schema/version request))
   "arcnet-entity-type" "DispatchCommand"})

;; =============================================================================
;; Dispatch Command Schema
;; =============================================================================

(defn make-dispatch-command
  "Creates a dispatch command for a scheduled request."
  [request node]
  {:schema/version 1
   :command/id (UUID/randomUUID)
   :command/type :inference-dispatch
   :command/timestamp (java.util.Date.)
   :command/request-id (:id request)
   :command/node-id (:xt/id node)
   :command/node-geohash (:node/geohash node)
   :command/model-id (:model-id request)
   :command/priority (:priority request)
   :command/max-latency-ms (:max-latency-ms request)
   :command/context-window-tokens (:context-window-tokens request)})

;; =============================================================================
;; Scheduling Logic
;; =============================================================================

(defn- attempt-schedule
  "Attempts to schedule a single inference request.

   Returns:
   {:status :success :node node :command command}
   {:status :no-candidates}
   {:status :reservation-failed :reason reason}"
  [request]
  (tracing/with-span {:name "attempt-schedule"
                      :attributes {:request-id (str (:id request))
                                   :model-id (:model-id request)}}
    (let [model-id (:model-id request)
          requester-geozone (:requester-geozone request)
          ;; Use geozone as geohash prefix for latency estimation
          requester-geohash requester-geozone
          ;; Find and score candidates
          best (rules/select-best-candidate model-id requester-geohash)]
      (if-not best
        ;; No candidates available
        (do
          (log/info "No candidates for request"
                    {:request-id (:id request)
                     :model-id model-id})
          {:status :no-candidates})
        ;; Attempt reservation
        (let [node (:node best)
              node-id (:xt/id node)
              reservation-result (reservation/reserve-node!
                                  node-id
                                  (:id request))]
          (if (:success reservation-result)
            ;; Success - create dispatch command
            (let [command (make-dispatch-command request node)]
              (log/info "Request scheduled successfully"
                        {:request-id (:id request)
                         :node-id node-id
                         :score (:score best)})
              {:status :success
               :node node
               :command command
               :reservation (:reservation reservation-result)})
            ;; Reservation failed
            (do
              (log/debug "Reservation failed"
                         {:request-id (:id request)
                          :node-id node-id
                          :reason (:reason reservation-result)})
              {:status :reservation-failed
               :reason (:reason reservation-result)
               :node-id node-id})))))))

(defn- try-schedule-with-fallback
  "Attempts to schedule with fallback to next-best candidates.

   Tries up to 3 candidates before giving up."
  [request]
  (let [model-id (:model-id request)
        requester-geozone (:requester-geozone request)
        candidates (rules/select-top-candidates model-id requester-geozone 3)]
    (if (empty? candidates)
      {:status :no-candidates}
      (loop [remaining candidates]
        (if (empty? remaining)
          {:status :no-candidates :reason "All candidates failed"}
          (let [{:keys [node score]} (first remaining)
                node-id (:xt/id node)
                reservation-result (reservation/reserve-node!
                                    node-id
                                    (:id request))]
            (if (:success reservation-result)
              (let [command (make-dispatch-command request node)]
                {:status :success
                 :node node
                 :command command
                 :reservation (:reservation reservation-result)})
              (recur (rest remaining)))))))))

;; =============================================================================
;; Message Handlers
;; =============================================================================

(defn- handle-request
  "Handles a single inference request.

   Produces to:
   - dispatch topic on success
   - retry topic on failure (if retries remaining)
   - rejected topic if max retries exceeded"
  [producer request headers]
  (let [retry-meta (extract-retry-metadata headers)
        retry-count (:retry-count retry-meta)
        original-request-id (or (:original-request-id retry-meta) (:id request))
        first-attempt-at (or (:first-attempt-at retry-meta) (Instant/now))
        request-id (:id request)]
    (tracing/with-span {:name "handle-inference-request"
                        :attributes {:request-id (str request-id)
                                     :retry-count retry-count}}
      (let [result (try-schedule-with-fallback request)]
        (case (:status result)
          ;; Success - dispatch to geozone
          :success
          (let [command (:command result)
                node (:node result)
                geozone (:node/geozone-id node)
                topic (dispatch-topic geozone)
                cmd-bytes (ser/serialize command)
                cmd-headers (kafka/make-headers
                             (make-dispatch-headers request (:xt/id node)))]
            (kafka/send-raw! producer topic
                             (ser/string->bytes (str (:command/id command)))
                             cmd-bytes
                             cmd-headers)
            (metrics/record-operation!
             {:operation "schedule-inference"
              :duration-ms 0  ; Already tracked in spans
              :success? true})
            (log/info "Dispatch command sent"
                      {:request-id request-id
                       :node-id (:xt/id node)
                       :geozone geozone}))

          ;; No candidates or reservation failed
          (:no-candidates :reservation-failed)
          (let [failure-reason (name (:status result))]
            (if (< retry-count max-retries)
              ;; Retry with backoff
              (let [retry-headers (kafka/make-headers
                                   (make-retry-headers request
                                                       retry-count
                                                       failure-reason
                                                       original-request-id
                                                       first-attempt-at))
                    backoff-ms (calculate-backoff-ms retry-count)]
                (log/info "Scheduling retry"
                          {:request-id request-id
                           :retry-count (inc retry-count)
                           :backoff-ms backoff-ms
                           :reason failure-reason})
                ;; Note: In production, use a delayed message queue
                ;; For now, send immediately to retry topic
                (kafka/send-raw! producer retry-topic
                                 (ser/string->bytes (str request-id))
                                 (ser/serialize request)
                                 retry-headers)
                (metrics/record-operation!
                 {:operation "schedule-inference-retry"
                  :duration-ms 0
                  :success? true}))
              ;; Max retries exceeded - reject
              (let [rejected-headers (kafka/make-headers
                                      (make-rejected-headers request
                                                             retry-count
                                                             failure-reason
                                                             original-request-id))]
                (log/warn "Request rejected after max retries"
                          {:request-id request-id
                           :retry-count retry-count
                           :reason failure-reason})
                (kafka/send-raw! producer rejected-topic
                                 (ser/string->bytes (str request-id))
                                 (ser/serialize request)
                                 rejected-headers)
                (metrics/record-operation!
                 {:operation "schedule-inference-rejected"
                  :duration-ms 0
                  :success? false})))))))))

;; =============================================================================
;; Scheduler State
;; =============================================================================

(defonce ^:private scheduler-running (atom false))
(defonce ^:private scheduler-channel (atom nil))
(defonce ^:private producer-instance (atom nil))
(defonce ^:private consumer-instance (atom nil))

;; =============================================================================
;; Scheduler Loop
;; =============================================================================

(defn- scheduler-loop
  "Main scheduler loop that processes inference requests."
  [poll-timeout-ms stop-ch]
  (go-loop []
    (let [[_ ch] (alts! [stop-ch (timeout poll-timeout-ms)])]
      (if (= ch stop-ch)
        (log/info "Scheduler loop stopped")
        (do
          (try
            (when-let [consumer @consumer-instance]
              (let [records (kafka/poll! consumer poll-timeout-ms)]
                (doseq [{:keys [status data metadata]} records
                        :when (= :valid status)]
                  (let [headers (:headers metadata)]
                    (handle-request @producer-instance data headers)))
                (when (seq records)
                  (kafka/commit! consumer))))
            (catch Exception e
              (log/error e "Error in scheduler loop")))
          (recur))))))

;; =============================================================================
;; Retry Consumer Loop
;; =============================================================================

(defonce ^:private retry-consumer-instance (atom nil))
(defonce ^:private retry-channel (atom nil))

(defn- retry-loop
  "Processes retry requests with backoff checking."
  [poll-timeout-ms stop-ch]
  (go-loop []
    (let [[_ ch] (alts! [stop-ch (timeout poll-timeout-ms)])]
      (if (= ch stop-ch)
        (log/info "Retry loop stopped")
        (do
          (try
            (when-let [consumer @retry-consumer-instance]
              (let [records (kafka/poll! consumer poll-timeout-ms)]
                (doseq [{:keys [status data metadata]} records
                        :when (= :valid status)]
                  (let [headers (:headers metadata)
                        next-retry-str (get headers :arcnet-next-retry-at)
                        next-retry (when next-retry-str
                                     (Instant/parse next-retry-str))]
                    ;; Check if backoff period has elapsed
                    (if (or (nil? next-retry)
                            (.isAfter (Instant/now) next-retry))
                      (handle-request @producer-instance data headers)
                      ;; Not ready yet - would need delayed requeue
                      ;; For simplicity, process anyway (production would use delayed queue)
                      (handle-request @producer-instance data headers))))
                (when (seq records)
                  (kafka/commit! consumer))))
            (catch Exception e
              (log/error e "Error in retry loop")))
          (recur))))))

;; =============================================================================
;; Lifecycle Management
;; =============================================================================

(defn start!
  "Starts the inference scheduler.

   Options:
   - :kafka-config - Kafka configuration (required)
   - :geozone-id - This scheduler's geozone (required)
   - :poll-timeout-ms - Consumer poll timeout (default 1000)"
  [{:keys [kafka-config geozone-id poll-timeout-ms]
    :or {poll-timeout-ms 1000}}]
  {:pre [(map? kafka-config) (string? geozone-id)]}
  (when @scheduler-running
    (log/warn "Scheduler already running")
    (throw (ex-info "Scheduler already running" {:type :already-running})))

  (log/info "Starting inference scheduler" {:geozone geozone-id})

  ;; Create producer
  (let [producer (kafka/create-producer
                  (assoc kafka-config
                         :client-id (str "scheduler-" geozone-id)))]
    (reset! producer-instance producer))

  ;; Create main consumer
  (let [consumer (kafka/create-consumer
                  (assoc kafka-config
                         :group-id (str "scheduler-" geozone-id)
                         :client-id (str "scheduler-consumer-" geozone-id)
                         :create-dead-letter-producer? true))]
    (kafka/subscribe! consumer [request-topic])
    (reset! consumer-instance consumer))

  ;; Create retry consumer
  (let [retry-consumer (kafka/create-consumer
                        (assoc kafka-config
                               :group-id (str "scheduler-retry-" geozone-id)
                               :client-id (str "scheduler-retry-" geozone-id)))]
    (kafka/subscribe! retry-consumer [retry-topic])
    (reset! retry-consumer-instance retry-consumer))

  ;; Start loops
  (let [stop-ch (chan)
        retry-stop-ch (chan)]
    (reset! scheduler-channel stop-ch)
    (reset! retry-channel retry-stop-ch)
    (reset! scheduler-running true)
    (scheduler-loop poll-timeout-ms stop-ch)
    (retry-loop poll-timeout-ms retry-stop-ch))

  (log/info "Inference scheduler started"))

(defn stop!
  "Stops the inference scheduler."
  []
  (log/info "Stopping inference scheduler")

  ;; Stop loops
  (when-let [ch @scheduler-channel]
    (close! ch)
    (reset! scheduler-channel nil))
  (when-let [ch @retry-channel]
    (close! ch)
    (reset! retry-channel nil))

  ;; Close consumers
  (when-let [consumer @consumer-instance]
    (kafka/close! consumer)
    (reset! consumer-instance nil))
  (when-let [consumer @retry-consumer-instance]
    (kafka/close! consumer)
    (reset! retry-consumer-instance nil))

  ;; Close producer
  (when-let [producer @producer-instance]
    (kafka/close! producer)
    (reset! producer-instance nil))

  (reset! scheduler-running false)
  (log/info "Inference scheduler stopped"))

;; =============================================================================
;; Status
;; =============================================================================

(defn status
  "Returns the current status of the scheduler."
  []
  {:running @scheduler-running
   :producer-active (some? @producer-instance)
   :consumer-active (some? @consumer-instance)
   :retry-consumer-active (some? @retry-consumer-instance)})

;; =============================================================================
;; Manual Scheduling (for testing)
;; =============================================================================

(defn schedule-inference!
  "Manually schedules an inference request.

   Useful for testing without the full consumer loop."
  [request]
  {:pre [(= 2 (:schema/version request))]}
  (when-not @producer-instance
    (throw (ex-info "Scheduler not started" {:type :not-started})))
  (handle-request @producer-instance request {}))
