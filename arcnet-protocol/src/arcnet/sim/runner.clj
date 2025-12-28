(ns arcnet.sim.runner
  "Main simulation orchestrator for ARCNet load testing.

   Coordinates all simulation components:
   - Node simulation (1000 nodes, 20 geozones)
   - Load generation (configurable RPS)
   - Chaos scenarios (toggleable)
   - Statistics collection and assertions

   Provides pre-configured test scenarios:
   - baseline: 5k RPS, no chaos, assert p99 < 100ms
   - stress: 10k RPS, no chaos
   - chaos-grid: 5k RPS with grid failure
   - chaos-partition: 5k RPS with Kafka partition
   - chaos-xtdb: 5k RPS with XTDB slowdown
   - chaos-all: 5k RPS with all chaos scenarios"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go go-loop <! >! chan close!
                                                   timeout alts! <!!]]
            [arcnet.sim.nodes :as nodes]
            [arcnet.sim.load :as load]
            [arcnet.sim.chaos :as chaos]
            [arcnet.sim.stats :as stats]
            [arcnet.observability.metrics :as metrics])
  (:import [java.time Instant Duration]
           [java.util Random]))

;; =============================================================================
;; Simulation State
;; =============================================================================

(defonce ^:private simulation-state
  (atom {:running false
         :scenario nil
         :started-at nil
         :config nil}))

;; =============================================================================
;; Pre-configured Scenarios
;; =============================================================================

(def scenarios
  "Pre-configured test scenarios."
  {:baseline
   {:name "Baseline Performance Test"
    :description "5k RPS with no chaos. Asserts p99 < 100ms."
    :node-count 1000
    :rps 5000
    :duration-seconds 60
    :chaos nil
    :assertions {:p99-threshold-ms 100
                 :rejection-threshold 0.05}}

   :stress
   {:name "High Load Stress Test"
    :description "10k RPS with no chaos. Tests system limits."
    :node-count 1000
    :rps 10000
    :duration-seconds 60
    :chaos nil
    :assertions {:p99-threshold-ms 200
                 :rejection-threshold 0.10}}

   :light
   {:name "Light Load Test"
    :description "1k RPS for quick validation."
    :node-count 1000
    :rps 1000
    :duration-seconds 30
    :chaos nil
    :assertions {:p99-threshold-ms 50
                 :rejection-threshold 0.01}}

   :chaos-grid
   {:name "Grid Failure Chaos Test"
    :description "5k RPS with grid failure in one geozone."
    :node-count 1000
    :rps 5000
    :duration-seconds 60
    :chaos {:type :grid-failure
            :target-geozone "9q8yyk"  ; San Francisco
            :start-after-seconds 10
            :duration-seconds 30}
    :assertions {:graceful-degradation true}}

   :chaos-partition
   {:name "Kafka Partition Chaos Test"
    :description "5k RPS with 5s message delays to one geozone."
    :node-count 1000
    :rps 5000
    :duration-seconds 60
    :chaos {:type :kafka-partition
            :target-geozone "dp3wjz"  ; Chicago
            :delay-ms 5000
            :start-after-seconds 10
            :duration-seconds 30}
    :assertions {:graceful-degradation true}}

   :chaos-xtdb
   {:name "XTDB Slowdown Chaos Test"
    :description "5k RPS with 500ms query latency."
    :node-count 1000
    :rps 5000
    :duration-seconds 60
    :chaos {:type :xtdb-slowdown
            :latency-ms 500
            :start-after-seconds 10
            :duration-seconds 30}
    :assertions {:graceful-degradation true}}

   :chaos-all
   {:name "Full Chaos Test"
    :description "5k RPS with all chaos scenarios active."
    :node-count 1000
    :rps 5000
    :duration-seconds 90
    :chaos {:type :all
            :target-geozone "9q8yyk"
            :start-after-seconds 15
            :duration-seconds 45}
    :assertions {:graceful-degradation true}}

   :endurance
   {:name "Endurance Test"
    :description "5k RPS for 10 minutes with random chaos."
    :node-count 1000
    :rps 5000
    :duration-seconds 600
    :chaos {:type :random
            :min-interval-ms 30000
            :max-interval-ms 120000}
    :assertions {:graceful-degradation true}}})

;; =============================================================================
;; Mock Scheduler (for simulation without real infrastructure)
;; =============================================================================

(defn- mock-schedule-request!
  "Simulates scheduling a request without real infrastructure.

   Uses node simulation state to determine outcomes."
  [request]
  (let [start-ms (System/currentTimeMillis)
        active-nodes (nodes/get-active-nodes)
        model-id (:model-id request)
        ;; Find nodes with the requested model
        candidates (filter #(some #{model-id} (:models-loaded %)) active-nodes)
        ;; Add XTDB slowdown if active
        _ (when (chaos/xtdb-slowdown-active?)
            (Thread/sleep (chaos/get-xtdb-slowdown-latency)))
        ;; Add partition delay if applicable
        geozone (:requester-geozone request)
        _ (when (chaos/kafka-partition-active? geozone)
            (Thread/sleep (chaos/get-kafka-partition-delay geozone)))
        ;; Determine outcome
        latency-ms (- (System/currentTimeMillis) start-ms)]

    ;; Record the request
    (stats/record-request!)
    (stats/record-latency! latency-ms)

    (cond
      ;; No candidates - reject
      (empty? candidates)
      (do
        (stats/record-rejection!)
        {:status :rejected :reason :no-candidates})

      ;; Simulate reservation conflicts (10% chance with multiple candidates)
      (and (> (count candidates) 3)
           (< (rand) 0.10))
      (do
        (stats/record-conflict!)
        (stats/record-retry!)
        ;; Retry succeeds 80% of the time
        (if (< (rand) 0.80)
          (do
            (stats/record-success!)
            {:status :success :node-id (:id (rand-nth candidates))})
          (do
            (stats/record-rejection!)
            {:status :rejected :reason :max-retries})))

      ;; Success
      :else
      (do
        (stats/record-success!)
        {:status :success :node-id (:id (rand-nth candidates))}))))

;; =============================================================================
;; Chaos Activation
;; =============================================================================

(defn- activate-chaos-for-scenario!
  "Activates chaos based on scenario configuration."
  [chaos-config]
  (when chaos-config
    (case (:type chaos-config)
      :grid-failure
      (chaos/activate-grid-failure! (:target-geozone chaos-config))

      :kafka-partition
      (chaos/activate-kafka-partition! (:target-geozone chaos-config)
                                        :delay-ms (:delay-ms chaos-config 5000))

      :xtdb-slowdown
      (chaos/activate-xtdb-slowdown! :latency-ms (:latency-ms chaos-config 500))

      :all
      (chaos/activate-all-chaos! (:target-geozone chaos-config))

      :random
      (chaos/start-random-chaos! {:min-interval-ms (:min-interval-ms chaos-config 30000)
                                   :max-interval-ms (:max-interval-ms chaos-config 120000)})

      nil)))

(defn- deactivate-chaos-for-scenario!
  "Deactivates chaos based on scenario configuration."
  [chaos-config]
  (when chaos-config
    (case (:type chaos-config)
      :random (chaos/stop-random-chaos!)
      (chaos/deactivate-all-chaos!))))

;; =============================================================================
;; Simulation Runner
;; =============================================================================

(defn run-scenario!
  "Runs a predefined scenario.

   Parameters:
   - scenario-key: One of :baseline, :stress, :chaos-grid, etc.

   Returns a result map with statistics and assertion results."
  [scenario-key & {:keys [on-request seed]
                   :or {seed (System/currentTimeMillis)}}]
  (let [scenario (get scenarios scenario-key)]
    (when-not scenario
      (throw (ex-info "Unknown scenario" {:scenario scenario-key
                                           :available (keys scenarios)})))

    (when (:running @simulation-state)
      (throw (ex-info "Simulation already running" {})))

    (log/info "========================================")
    (log/info (format "Starting scenario: %s" (:name scenario)))
    (log/info (:description scenario))
    (log/info "========================================")

    ;; Update state
    (swap! simulation-state assoc
           :running true
           :scenario scenario-key
           :started-at (Instant/now)
           :config scenario)

    ;; Reset statistics
    (stats/reset-stats!)

    ;; Start metrics updater
    (stats/start-metrics-updater!)

    (try
      ;; Start node simulation
      (nodes/start! {:node-count (:node-count scenario)
                     :seed seed
                     :tick-interval-ms 1000})

      ;; Wait for nodes to initialize
      (Thread/sleep 2000)

      (log/info "Node simulation started" {:stats (nodes/get-fleet-stats)})

      ;; Prepare chaos (if scheduled)
      (let [chaos-config (:chaos scenario)
            chaos-ch (when chaos-config
                       (go
                         ;; Wait for start delay
                         (when-let [delay-s (:start-after-seconds chaos-config)]
                           (<! (timeout (* delay-s 1000))))
                         (log/warn "Activating chaos...")
                         (activate-chaos-for-scenario! chaos-config)
                         ;; Wait for chaos duration
                         (when-let [duration-s (:duration-seconds chaos-config)]
                           (<! (timeout (* duration-s 1000)))
                           (log/info "Deactivating chaos...")
                           (deactivate-chaos-for-scenario! chaos-config))))]

        ;; Start load generation
        (let [request-handler (or on-request mock-schedule-request!)
              load-ch (load/start! {:rps (:rps scenario)
                                    :seed seed
                                    :on-request request-handler})]

          ;; Run for duration
          (log/info (format "Running for %d seconds..." (:duration-seconds scenario)))
          (Thread/sleep (* (:duration-seconds scenario) 1000))

          ;; Stop load generation
          (load/stop!))

        ;; Wait for chaos channel to complete
        (when chaos-ch
          (<!! chaos-ch)))

      ;; Generate report
      (stats/print-report!)

      ;; Run assertions
      (let [assertions-config (:assertions scenario)
            chaos-was-active? (some? (:chaos scenario))
            assertion-results (if (:graceful-degradation assertions-config)
                                (stats/assert-graceful-degradation!)
                                (stats/run-all-assertions!
                                 :chaos-active? chaos-was-active?))]

        ;; Log assertion results
        (if (:passed assertion-results)
          (log/info "All assertions PASSED")
          (log/error "Some assertions FAILED" assertion-results))

        {:scenario scenario-key
         :config scenario
         :duration-seconds (:duration-seconds scenario)
         :stats (stats/get-stats)
         :report (stats/generate-report)
         :assertions assertion-results})

      (finally
        ;; Cleanup
        (chaos/reset-chaos!)
        (nodes/stop!)
        (stats/stop-metrics-updater!)
        (swap! simulation-state assoc :running false)
        (log/info "Simulation complete")))))

;; =============================================================================
;; Quick Test Runners
;; =============================================================================

(defn run-baseline!
  "Runs baseline performance test (5k RPS, no chaos)."
  []
  (run-scenario! :baseline))

(defn run-stress!
  "Runs high load stress test (10k RPS, no chaos)."
  []
  (run-scenario! :stress))

(defn run-light!
  "Runs light load test (1k RPS, 30s)."
  []
  (run-scenario! :light))

(defn run-chaos-all!
  "Runs full chaos test."
  []
  (run-scenario! :chaos-all))

;; =============================================================================
;; Custom Scenario Builder
;; =============================================================================

(defn run-custom!
  "Runs a custom scenario.

   Options:
   - :rps - Requests per second (default 1000)
   - :duration-seconds - Test duration (default 60)
   - :node-count - Number of simulated nodes (default 1000)
   - :chaos-type - :grid-failure, :kafka-partition, :xtdb-slowdown, :all, or nil
   - :chaos-geozone - Target geozone for chaos
   - :chaos-delay-seconds - Delay before chaos starts
   - :chaos-duration-seconds - How long chaos lasts
   - :p99-threshold-ms - p99 latency assertion threshold
   - :on-request - Custom request handler function"
  [{:keys [rps duration-seconds node-count
           chaos-type chaos-geozone chaos-delay-seconds chaos-duration-seconds
           p99-threshold-ms on-request seed]
    :or {rps 1000
         duration-seconds 60
         node-count 1000
         seed (System/currentTimeMillis)}}]
  (let [custom-scenario
        {:name "Custom Scenario"
         :description "User-defined test scenario"
         :node-count node-count
         :rps rps
         :duration-seconds duration-seconds
         :chaos (when chaos-type
                  {:type chaos-type
                   :target-geozone (or chaos-geozone "9q8yyk")
                   :start-after-seconds (or chaos-delay-seconds 10)
                   :duration-seconds (or chaos-duration-seconds 30)})
         :assertions (if chaos-type
                       {:graceful-degradation true}
                       {:p99-threshold-ms (or p99-threshold-ms 100)
                        :rejection-threshold 0.05})}]

    ;; Temporarily add custom scenario
    (alter-var-root #'scenarios assoc :custom custom-scenario)

    (try
      (run-scenario! :custom :on-request on-request :seed seed)
      (finally
        ;; Remove custom scenario
        (alter-var-root #'scenarios dissoc :custom)))))

;; =============================================================================
;; Status and Control
;; =============================================================================

(defn status
  "Returns current simulation status."
  []
  (merge @simulation-state
         {:nodes (nodes/status)
          :load (load/status)
          :chaos (chaos/status)
          :stats (stats/get-stats)}))

(defn stop!
  "Emergency stop of all simulation components."
  []
  (log/warn "Emergency stop requested")
  (try (load/stop!) (catch Exception _))
  (try (nodes/stop!) (catch Exception _))
  (try (chaos/reset-chaos!) (catch Exception _))
  (try (stats/stop-metrics-updater!) (catch Exception _))
  (swap! simulation-state assoc :running false)
  (log/info "All simulation components stopped"))

;; =============================================================================
;; Available Scenarios List
;; =============================================================================

(defn list-scenarios
  "Returns a list of available scenarios with descriptions."
  []
  (into {} (map (fn [[k v]] [k {:name (:name v)
                                  :description (:description v)
                                  :rps (:rps v)
                                  :duration-seconds (:duration-seconds v)
                                  :has-chaos (some? (:chaos v))}])
                scenarios)))

(defn print-scenarios!
  "Prints available scenarios."
  []
  (println "\nAvailable Scenarios:")
  (println "====================")
  (doseq [[k v] (sort-by #(:rps (val %)) scenarios)]
    (println (format "\n:%s - %s" (name k) (:name v)))
    (println (format "  %s" (:description v)))
    (println (format "  RPS: %,d | Duration: %ds | Chaos: %s"
                     (:rps v)
                     (:duration-seconds v)
                     (if (:chaos v) "Yes" "No"))))
  (println "\nUsage: (run-scenario! :baseline)"))
