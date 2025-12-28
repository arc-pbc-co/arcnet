(ns arcnet.bridge.orchestrator
  "ORNL Bridge Orchestrator for training job routing.

   Consumes arc.job.submission and routes jobs based on classification:
   - HPC-bound jobs: Initiate Globus transfer, track in arc.bridge.pending
   - Federated jobs: Route directly to arc.scheduler.training

   Separate poller monitors pending transfers and on completion
   sends to ornl.bridge.ingress for ORNL processing.

   Topics:
   - arc.job.submission (input)
   - arc.bridge.pending (HPC jobs awaiting transfer)
   - ornl.bridge.ingress (jobs ready for ORNL)
   - arc.scheduler.training (federated jobs)"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [<! >! go go-loop chan close!
                                                   timeout alts!]]
            [arcnet.bridge.classifier :as classifier]
            [arcnet.bridge.data-mover :as data-mover]
            [arcnet.transport.kafka :as kafka]
            [arcnet.transport.serialization :as ser]
            [arcnet.observability.metrics :as metrics]
            [arcnet.observability.tracing :as tracing])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Topics
;; =============================================================================

(def ^:const submission-topic "arc.job.submission")
(def ^:const pending-topic "arc.bridge.pending")
(def ^:const ornl-ingress-topic "ornl.bridge.ingress")
(def ^:const training-scheduler-topic "arc.scheduler.training")
(def ^:const failed-topic "arc.bridge.failed")

;; =============================================================================
;; Pending Job Schema
;; =============================================================================

(defn make-pending-job
  "Creates a pending job record for tracking HPC transfers."
  [job task-id destination-path]
  {:schema/version 1
   :pending/id (UUID/randomUUID)
   :pending/job-id (:id job)
   :pending/globus-task-id task-id
   :pending/destination-path destination-path
   :pending/submitted-at (Instant/now)
   :pending/status :transferring
   :pending/retry-count 0
   :pending/original-job job})

(defn make-ornl-job
  "Creates a job record ready for ORNL ingress."
  [pending-job transfer-status]
  {:schema/version 1
   :ornl/id (UUID/randomUUID)
   :ornl/job-id (get-in pending-job [:pending/original-job :id])
   :ornl/dataset-path (:pending/destination-path pending-job)
   :ornl/transfer-completed-at (Instant/now)
   :ornl/bytes-transferred (:bytes-transferred transfer-status)
   :ornl/files-transferred (:files-transferred transfer-status)
   :ornl/original-job (:pending/original-job pending-job)
   :ornl/classification (classifier/classify-workload (:pending/original-job pending-job))})

(defn make-failed-job
  "Creates a failed job record."
  [job reason error]
  {:schema/version 1
   :failed/id (UUID/randomUUID)
   :failed/job-id (:id job)
   :failed/failed-at (Instant/now)
   :failed/reason reason
   :failed/error error
   :failed/original-job job})

;; =============================================================================
;; Header Builders
;; =============================================================================

(defn- make-pending-headers
  [pending-job]
  {"arcnet-entity-type" "PendingJob"
   "arcnet-schema-version" "1"
   "arcnet-job-id" (str (:pending/job-id pending-job))
   "arcnet-globus-task-id" (:pending/globus-task-id pending-job)
   "arcnet-submitted-at" (str (:pending/submitted-at pending-job))})

(defn- make-ornl-headers
  [ornl-job]
  {"arcnet-entity-type" "ORNLJob"
   "arcnet-schema-version" "1"
   "arcnet-job-id" (str (:ornl/job-id ornl-job))
   "arcnet-transfer-completed-at" (str (:ornl/transfer-completed-at ornl-job))})

(defn- make-training-headers
  [job classification]
  {"arcnet-entity-type" "TrainingJob"
   "arcnet-schema-version" (str (:schema/version job))
   "arcnet-job-id" (str (:id job))
   "arcnet-classification-target" (name (:target classification))
   "arcnet-classification-reason" (:reason classification)})

(defn- make-failed-headers
  [failed-job]
  {"arcnet-entity-type" "FailedJob"
   "arcnet-schema-version" "1"
   "arcnet-job-id" (str (:failed/job-id failed-job))
   "arcnet-failed-at" (str (:failed/failed-at failed-job))
   "arcnet-failure-reason" (:failed/reason failed-job)})

;; =============================================================================
;; Job Routing
;; =============================================================================

(defn- route-to-federated!
  "Routes a job to the federated training scheduler."
  [producer job classification]
  (tracing/with-span {:name "route-to-federated"
                      :attributes {:job-id (str (:id job))}}
    (let [headers (kafka/make-headers (make-training-headers job classification))
          key-bytes (ser/string->bytes (str (:id job)))
          value-bytes (ser/serialize job)]
      (kafka/send-raw! producer training-scheduler-topic key-bytes value-bytes headers)
      (log/info "Job routed to federated scheduler"
                {:job-id (:id job)
                 :reason (:reason classification)})
      (metrics/record-operation!
       {:operation "route-federated"
        :duration-ms 0
        :success? true}))))

(defn- route-to-hpc!
  "Routes a job to HPC via Globus transfer."
  [producer job classification]
  (tracing/with-span {:name "route-to-hpc"
                      :attributes {:job-id (str (:id job))}}
    (let [timer (metrics/start-timer)
          dataset-uri (:dataset-uri job)
          ;; Initiate Globus transfer with retry
          transfer-result (data-mover/initiate-transfer-with-retry!
                           (:id job)
                           dataset-uri)]
      (if (:success transfer-result)
        ;; Transfer initiated - create pending job
        (let [pending-job (make-pending-job
                           job
                           (:task-id transfer-result)
                           (:destination-path transfer-result))
              headers (kafka/make-headers (make-pending-headers pending-job))
              key-bytes (ser/string->bytes (str (:pending/id pending-job)))
              value-bytes (ser/serialize pending-job)]
          (kafka/send-raw! producer pending-topic key-bytes value-bytes headers)
          (log/info "HPC job transfer initiated"
                    {:job-id (:id job)
                     :task-id (:task-id transfer-result)
                     :destination (:destination-path transfer-result)})
          (metrics/record-operation!
           {:operation "route-hpc-transfer"
            :duration-ms (timer)
            :success? true})
          {:success true
           :task-id (:task-id transfer-result)
           :pending-id (:pending/id pending-job)})
        ;; Transfer failed
        (let [failed-job (make-failed-job job "transfer-initiation-failed" (:error transfer-result))
              headers (kafka/make-headers (make-failed-headers failed-job))
              key-bytes (ser/string->bytes (str (:failed/id failed-job)))
              value-bytes (ser/serialize failed-job)]
          (kafka/send-raw! producer failed-topic key-bytes value-bytes headers)
          (log/error "HPC job transfer initiation failed"
                     {:job-id (:id job)
                      :error (:error transfer-result)})
          (metrics/record-operation!
           {:operation "route-hpc-transfer"
            :duration-ms (timer)
            :success? false})
          {:success false
           :error (:error transfer-result)})))))

(defn- handle-submission
  "Handles a single job submission."
  [producer job]
  (tracing/with-span {:name "handle-job-submission"
                      :attributes {:job-id (str (:id job))}}
    (let [classification (classifier/classify-workload job)
          target (:target classification)]
      (log/info "Job classified"
                {:job-id (:id job)
                 :target target
                 :reason (:reason classification)
                 :dataset-size-gb (:dataset-size-gb job)
                 :estimated-flops (:estimated-flops job)})
      (case target
        :hpc (route-to-hpc! producer job classification)
        :federated (route-to-federated! producer job classification)))))

;; =============================================================================
;; Pending Transfer Poller
;; =============================================================================

(defn- check-pending-transfer
  "Checks the status of a pending transfer and takes appropriate action."
  [producer pending-job]
  (let [task-id (:pending/globus-task-id pending-job)
        job-id (:pending/job-id pending-job)
        status (data-mover/poll-transfer-status task-id)]
    (log/debug "Polled transfer status"
               {:job-id job-id
                :task-id task-id
                :status (:status status)})
    (case (:status status)
      ;; Transfer succeeded - send to ORNL
      :succeeded
      (let [ornl-job (make-ornl-job pending-job status)
            headers (kafka/make-headers (make-ornl-headers ornl-job))
            key-bytes (ser/string->bytes (str (:ornl/id ornl-job)))
            value-bytes (ser/serialize ornl-job)]
        (kafka/send-raw! producer ornl-ingress-topic key-bytes value-bytes headers)
        (log/info "Transfer completed, job sent to ORNL"
                  {:job-id job-id
                   :task-id task-id
                   :bytes-transferred (:bytes-transferred status)})
        (metrics/record-operation!
         {:operation "transfer-completed"
          :duration-ms 0
          :success? true})
        {:action :completed
         :job-id job-id})

      ;; Transfer failed
      :failed
      (let [original-job (:pending/original-job pending-job)
            failed-job (make-failed-job original-job "transfer-failed"
                                        (:nice-status-short-description status))
            headers (kafka/make-headers (make-failed-headers failed-job))
            key-bytes (ser/string->bytes (str (:failed/id failed-job)))
            value-bytes (ser/serialize failed-job)]
        (kafka/send-raw! producer failed-topic key-bytes value-bytes headers)
        (log/error "Transfer failed"
                   {:job-id job-id
                    :task-id task-id
                    :error (:nice-status-short-description status)})
        (metrics/record-operation!
         {:operation "transfer-failed"
          :duration-ms 0
          :success? false})
        {:action :failed
         :job-id job-id
         :error (:nice-status-short-description status)})

      ;; Transfer canceled
      :canceled
      (let [original-job (:pending/original-job pending-job)
            failed-job (make-failed-job original-job "transfer-canceled" "Transfer was canceled")
            headers (kafka/make-headers (make-failed-headers failed-job))
            key-bytes (ser/string->bytes (str (:failed/id failed-job)))
            value-bytes (ser/serialize failed-job)]
        (kafka/send-raw! producer failed-topic key-bytes value-bytes headers)
        (log/warn "Transfer canceled"
                  {:job-id job-id
                   :task-id task-id})
        {:action :canceled
         :job-id job-id})

      ;; Still pending or active - re-queue for later polling
      (:pending :active)
      (let [;; Re-publish to pending topic for continued polling
            updated-pending (update pending-job :pending/retry-count inc)
            headers (kafka/make-headers (make-pending-headers updated-pending))
            key-bytes (ser/string->bytes (str (:pending/id updated-pending)))
            value-bytes (ser/serialize updated-pending)]
        ;; Only re-queue if reasonable time has passed
        ;; In production, this would use a delayed queue
        (kafka/send-raw! producer pending-topic key-bytes value-bytes headers)
        (log/debug "Transfer still in progress"
                   {:job-id job-id
                    :task-id task-id
                    :status (:status status)
                    :bytes-transferred (:bytes-transferred status)})
        {:action :pending
         :job-id job-id
         :status (:status status)})

      ;; Unknown status
      {:action :unknown
       :job-id job-id
       :status status})))

;; =============================================================================
;; Orchestrator State
;; =============================================================================

(defonce ^:private orchestrator-running (atom false))
(defonce ^:private submission-consumer (atom nil))
(defonce ^:private pending-consumer (atom nil))
(defonce ^:private producer-instance (atom nil))
(defonce ^:private stop-channels (atom nil))

;; =============================================================================
;; Consumer Loops
;; =============================================================================

(defn- submission-loop
  "Main loop processing job submissions."
  [poll-timeout-ms stop-ch]
  (go-loop []
    (let [[_ ch] (alts! [stop-ch (timeout poll-timeout-ms)])]
      (if (= ch stop-ch)
        (log/info "Submission loop stopped")
        (do
          (try
            (when-let [consumer @submission-consumer]
              (let [records (kafka/poll! consumer poll-timeout-ms)]
                (doseq [{:keys [status data]} records
                        :when (= :valid status)]
                  (handle-submission @producer-instance data))
                (when (seq records)
                  (kafka/commit! consumer))))
            (catch Exception e
              (log/error e "Error in submission loop")))
          (recur))))))

(defn- pending-loop
  "Loop polling pending transfers."
  [poll-timeout-ms poll-interval-ms stop-ch]
  (go-loop []
    (let [[_ ch] (alts! [stop-ch (timeout poll-interval-ms)])]
      (if (= ch stop-ch)
        (log/info "Pending loop stopped")
        (do
          (try
            (when-let [consumer @pending-consumer]
              (let [records (kafka/poll! consumer poll-timeout-ms)]
                (doseq [{:keys [status data]} records
                        :when (= :valid status)]
                  (check-pending-transfer @producer-instance data))
                (when (seq records)
                  (kafka/commit! consumer))))
            (catch Exception e
              (log/error e "Error in pending loop")))
          (recur))))))

;; =============================================================================
;; Lifecycle Management
;; =============================================================================

(defn start!
  "Starts the bridge orchestrator.

   Options:
   - :kafka-config - Kafka configuration (required)
   - :poll-timeout-ms - Consumer poll timeout (default 1000)
   - :pending-poll-interval-ms - How often to check pending transfers (default 30000)"
  [{:keys [kafka-config poll-timeout-ms pending-poll-interval-ms]
    :or {poll-timeout-ms 1000
         pending-poll-interval-ms 30000}}]
  {:pre [(map? kafka-config)]}
  (when @orchestrator-running
    (log/warn "Orchestrator already running")
    (throw (ex-info "Orchestrator already running" {:type :already-running})))

  (log/info "Starting bridge orchestrator")

  ;; Create producer
  (let [producer (kafka/create-producer
                  (assoc kafka-config :client-id "bridge-orchestrator"))]
    (reset! producer-instance producer))

  ;; Create submission consumer
  (let [consumer (kafka/create-consumer
                  (assoc kafka-config
                         :group-id "bridge-orchestrator-submissions"
                         :client-id "bridge-submission-consumer"))]
    (kafka/subscribe! consumer [submission-topic])
    (reset! submission-consumer consumer))

  ;; Create pending consumer
  (let [consumer (kafka/create-consumer
                  (assoc kafka-config
                         :group-id "bridge-orchestrator-pending"
                         :client-id "bridge-pending-consumer"))]
    (kafka/subscribe! consumer [pending-topic])
    (reset! pending-consumer consumer))

  ;; Start loops
  (let [submission-stop-ch (chan)
        pending-stop-ch (chan)]
    (reset! stop-channels {:submission submission-stop-ch
                           :pending pending-stop-ch})
    (reset! orchestrator-running true)
    (submission-loop poll-timeout-ms submission-stop-ch)
    (pending-loop poll-timeout-ms pending-poll-interval-ms pending-stop-ch))

  (log/info "Bridge orchestrator started"))

(defn stop!
  "Stops the bridge orchestrator."
  []
  (log/info "Stopping bridge orchestrator")

  ;; Stop loops
  (when-let [channels @stop-channels]
    (doseq [[name ch] channels]
      (close! ch))
    (reset! stop-channels nil))

  ;; Close consumers
  (when-let [consumer @submission-consumer]
    (kafka/close! consumer)
    (reset! submission-consumer nil))
  (when-let [consumer @pending-consumer]
    (kafka/close! consumer)
    (reset! pending-consumer nil))

  ;; Close producer
  (when-let [producer @producer-instance]
    (kafka/close! producer)
    (reset! producer-instance nil))

  (reset! orchestrator-running false)
  (log/info "Bridge orchestrator stopped"))

;; =============================================================================
;; Status
;; =============================================================================

(defn status
  "Returns the current status of the orchestrator."
  []
  {:running @orchestrator-running
   :producer-active (some? @producer-instance)
   :submission-consumer-active (some? @submission-consumer)
   :pending-consumer-active (some? @pending-consumer)})

;; =============================================================================
;; Manual Routing (for testing)
;; =============================================================================

(defn route-job!
  "Manually routes a job through the orchestrator.

   Useful for testing without the full consumer loop."
  [job]
  (when-not @producer-instance
    (throw (ex-info "Orchestrator not started" {:type :not-started})))
  (handle-submission @producer-instance job))

(defn check-transfer!
  "Manually checks a pending transfer.

   Useful for testing the pending poller logic."
  [pending-job]
  (when-not @producer-instance
    (throw (ex-info "Orchestrator not started" {:type :not-started})))
  (check-pending-transfer @producer-instance pending-job))
