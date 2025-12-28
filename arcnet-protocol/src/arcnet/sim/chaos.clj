(ns arcnet.sim.chaos
  "Chaos engineering scenarios for ARCNet stress testing.

   Implements toggleable failure scenarios:
   - grid-failure: 30% of nodes in one geozone switch to battery, 10% go offline
   - kafka-partition: Messages to one geozone delayed by 5 seconds
   - xtdb-slowdown: Inject 500ms latency on all queries

   Chaos scenarios are designed to test graceful degradation and recovery."
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go go-loop <! >! chan close!
                                                   timeout alts!]]
            [arcnet.sim.nodes :as nodes]
            [arcnet.observability.metrics :as metrics])
  (:import [java.util Random]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong]
           [java.time Instant Duration]))

;; =============================================================================
;; Chaos State
;; =============================================================================

(defonce ^:private chaos-flags
  "Active chaos scenarios."
  (atom {:grid-failure {:active false
                        :target-geozone nil
                        :started-at nil}
         :kafka-partition {:active false
                           :target-geozone nil
                           :delay-ms 5000
                           :started-at nil}
         :xtdb-slowdown {:active false
                         :latency-ms 500
                         :started-at nil}}))

(defonce ^:private chaos-metrics
  "Metrics tracked during chaos."
  (atom {:events []
         :recoveries []}))

;; =============================================================================
;; Prometheus Metrics for Chaos
;; =============================================================================

(defonce chaos-scenarios-active
  (-> (io.prometheus.client.Gauge/build)
      (.name "sim_chaos_scenarios_active")
      (.help "Number of active chaos scenarios")
      (.register (metrics/get-registry))))

(defonce chaos-events-total
  (-> (io.prometheus.client.Counter/build)
      (.name "sim_chaos_events_total")
      (.help "Total chaos events triggered")
      (.labelNames (into-array String ["scenario" "action"]))
      (.register (metrics/get-registry))))

(defn- record-chaos-event!
  "Records a chaos event."
  [scenario action]
  (-> chaos-events-total
      (.labels (into-array String [(name scenario) (name action)]))
      (.inc))
  (swap! chaos-metrics update :events conj
         {:scenario scenario
          :action action
          :timestamp (Instant/now)}))

;; =============================================================================
;; Grid Failure Scenario
;; =============================================================================

(defn activate-grid-failure!
  "Activates grid failure chaos in a geozone.

   Effects:
   - 30% of nodes in geozone switch from grid to battery power
   - 10% of nodes go offline (stale)
   - Remaining nodes continue on their current power source

   This simulates a regional power grid outage."
  [geozone]
  (log/warn "CHAOS: Activating grid failure" {:geozone geozone})

  ;; Update chaos flags
  (swap! chaos-flags assoc-in [:grid-failure]
         {:active true
          :target-geozone geozone
          :started-at (Instant/now)})

  ;; Apply to nodes
  (nodes/inject-grid-failure! geozone)

  ;; Update metrics
  (.inc chaos-scenarios-active)
  (record-chaos-event! :grid-failure :activated)

  (log/warn "CHAOS: Grid failure active" {:geozone geozone
                                           :fleet-stats (nodes/get-fleet-stats)}))

(defn deactivate-grid-failure!
  "Recovers from grid failure chaos."
  []
  (when-let [geozone (get-in @chaos-flags [:grid-failure :target-geozone])]
    (log/info "CHAOS: Recovering from grid failure" {:geozone geozone})

    ;; Recover nodes
    (nodes/recover-grid! geozone)

    ;; Update flags
    (swap! chaos-flags assoc-in [:grid-failure]
           {:active false
            :target-geozone nil
            :started-at nil})

    ;; Update metrics
    (.dec chaos-scenarios-active)
    (record-chaos-event! :grid-failure :deactivated)

    (log/info "CHAOS: Grid failure recovered")))

;; =============================================================================
;; Kafka Partition Scenario
;; =============================================================================

;; Delay queue for simulating network partitions
(defonce ^:private partition-delay-queue (atom {}))

(defn kafka-partition-active?
  "Returns true if Kafka partition chaos is active for a geozone."
  [geozone]
  (and (get-in @chaos-flags [:kafka-partition :active])
       (= geozone (get-in @chaos-flags [:kafka-partition :target-geozone]))))

(defn get-kafka-partition-delay
  "Returns delay in ms for messages to a geozone, or 0 if no delay."
  [geozone]
  (if (kafka-partition-active? geozone)
    (get-in @chaos-flags [:kafka-partition :delay-ms] 0)
    0))

(defn activate-kafka-partition!
  "Activates Kafka network partition chaos.

   Effects:
   - All messages destined for the target geozone are delayed by 5 seconds
   - Simulates network partition or Kafka broker issues

   Note: The actual delay must be implemented by the transport layer
   checking `kafka-partition-active?` and `get-kafka-partition-delay`."
  [geozone & {:keys [delay-ms] :or {delay-ms 5000}}]
  (log/warn "CHAOS: Activating Kafka partition" {:geozone geozone
                                                  :delay-ms delay-ms})

  (swap! chaos-flags assoc-in [:kafka-partition]
         {:active true
          :target-geozone geozone
          :delay-ms delay-ms
          :started-at (Instant/now)})

  (.inc chaos-scenarios-active)
  (record-chaos-event! :kafka-partition :activated)

  (log/warn "CHAOS: Kafka partition active"))

(defn deactivate-kafka-partition!
  "Recovers from Kafka partition chaos."
  []
  (when (get-in @chaos-flags [:kafka-partition :active])
    (log/info "CHAOS: Recovering from Kafka partition")

    (swap! chaos-flags assoc-in [:kafka-partition]
           {:active false
            :target-geozone nil
            :delay-ms 5000
            :started-at nil})

    (.dec chaos-scenarios-active)
    (record-chaos-event! :kafka-partition :deactivated)

    (log/info "CHAOS: Kafka partition recovered")))

(defn with-partition-delay
  "Wraps a function to apply partition delay if chaos is active.

   Use this in transport layer to simulate network delays."
  [geozone f]
  (let [delay-ms (get-kafka-partition-delay geozone)]
    (if (pos? delay-ms)
      (do
        (Thread/sleep delay-ms)
        (f))
      (f))))

;; =============================================================================
;; XTDB Slowdown Scenario
;; =============================================================================

(defn xtdb-slowdown-active?
  "Returns true if XTDB slowdown chaos is active."
  []
  (get-in @chaos-flags [:xtdb-slowdown :active] false))

(defn get-xtdb-slowdown-latency
  "Returns additional latency in ms for XTDB queries, or 0 if inactive."
  []
  (if (xtdb-slowdown-active?)
    (get-in @chaos-flags [:xtdb-slowdown :latency-ms] 0)
    0))

(defn activate-xtdb-slowdown!
  "Activates XTDB query slowdown chaos.

   Effects:
   - All XTDB queries are delayed by the specified latency (default 500ms)
   - Simulates database performance degradation

   Note: The actual delay must be implemented by the state layer
   checking `xtdb-slowdown-active?` and using `with-xtdb-delay`."
  [& {:keys [latency-ms] :or {latency-ms 500}}]
  (log/warn "CHAOS: Activating XTDB slowdown" {:latency-ms latency-ms})

  (swap! chaos-flags assoc-in [:xtdb-slowdown]
         {:active true
          :latency-ms latency-ms
          :started-at (Instant/now)})

  (.inc chaos-scenarios-active)
  (record-chaos-event! :xtdb-slowdown :activated)

  (log/warn "CHAOS: XTDB slowdown active"))

(defn deactivate-xtdb-slowdown!
  "Recovers from XTDB slowdown chaos."
  []
  (when (xtdb-slowdown-active?)
    (log/info "CHAOS: Recovering from XTDB slowdown")

    (swap! chaos-flags assoc-in [:xtdb-slowdown]
           {:active false
            :latency-ms 500
            :started-at nil})

    (.dec chaos-scenarios-active)
    (record-chaos-event! :xtdb-slowdown :deactivated)

    (log/info "CHAOS: XTDB slowdown recovered")))

(defmacro with-xtdb-delay
  "Macro to wrap XTDB operations with chaos delay.

   Use in state layer queries:
   (with-xtdb-delay
     (xtdb/query ...))"
  [& body]
  `(do
     (when (xtdb-slowdown-active?)
       (Thread/sleep (get-xtdb-slowdown-latency)))
     ~@body))

;; =============================================================================
;; Chaos Orchestration
;; =============================================================================

(defn activate-all-chaos!
  "Activates all chaos scenarios simultaneously.

   This is the most extreme test scenario."
  [target-geozone]
  (log/error "CHAOS: ACTIVATING ALL CHAOS SCENARIOS")
  (activate-grid-failure! target-geozone)
  (activate-kafka-partition! target-geozone)
  (activate-xtdb-slowdown!))

(defn deactivate-all-chaos!
  "Deactivates all chaos scenarios."
  []
  (log/info "CHAOS: Deactivating all chaos scenarios")
  (deactivate-grid-failure!)
  (deactivate-kafka-partition!)
  (deactivate-xtdb-slowdown!))

(defn reset-chaos!
  "Resets all chaos state to clean baseline."
  []
  (deactivate-all-chaos!)
  (reset! chaos-metrics {:events [] :recoveries []})
  (log/info "CHAOS: All chaos state reset"))

;; =============================================================================
;; Timed Chaos Scenarios
;; =============================================================================

(defonce ^:private chaos-scheduler (atom nil))

(defn schedule-chaos!
  "Schedules chaos to activate after a delay and optionally auto-recover.

   Options:
   - :scenario - :grid-failure, :kafka-partition, or :xtdb-slowdown
   - :target-geozone - Geozone to target (for grid-failure and kafka-partition)
   - :activate-after-ms - Delay before activation
   - :duration-ms - How long chaos lasts before auto-recovery (nil for indefinite)

   Returns a channel that closes when chaos is complete."
  [{:keys [scenario target-geozone activate-after-ms duration-ms]
    :or {activate-after-ms 0}}]
  (let [done-ch (chan)]
    (go
      ;; Wait for activation delay
      (when (pos? activate-after-ms)
        (<! (timeout activate-after-ms)))

      ;; Activate chaos
      (case scenario
        :grid-failure (activate-grid-failure! target-geozone)
        :kafka-partition (activate-kafka-partition! target-geozone)
        :xtdb-slowdown (activate-xtdb-slowdown!))

      ;; Auto-recover after duration if specified
      (when duration-ms
        (<! (timeout duration-ms))
        (case scenario
          :grid-failure (deactivate-grid-failure!)
          :kafka-partition (deactivate-kafka-partition!)
          :xtdb-slowdown (deactivate-xtdb-slowdown!)))

      (close! done-ch))
    done-ch))

(defn run-chaos-sequence!
  "Runs a sequence of chaos scenarios.

   Each scenario is a map with:
   - :scenario - Type of chaos
   - :target-geozone - Target (if applicable)
   - :delay-ms - Delay before this scenario
   - :duration-ms - Duration of this scenario

   Example:
   (run-chaos-sequence!
     [{:scenario :grid-failure :target-geozone \"9q8yyk\" :delay-ms 0 :duration-ms 30000}
      {:scenario :kafka-partition :target-geozone \"dp3wjz\" :delay-ms 10000 :duration-ms 20000}])"
  [scenarios]
  (let [done-ch (chan)]
    (go
      (doseq [scenario scenarios]
        (let [ch (schedule-chaos! scenario)]
          (<! ch)))
      (close! done-ch))
    done-ch))

;; =============================================================================
;; Status
;; =============================================================================

(defn status
  "Returns current chaos status."
  []
  {:flags @chaos-flags
   :active-count (count (filter #(get-in % [1 :active]) @chaos-flags))
   :recent-events (take-last 10 (:events @chaos-metrics))})

(defn chaos-active?
  "Returns true if any chaos scenario is active."
  []
  (some #(get-in % [1 :active]) @chaos-flags))

;; =============================================================================
;; Random Chaos Mode
;; =============================================================================

(defonce ^:private random-chaos-running (atom false))
(defonce ^:private random-chaos-channel (atom nil))

(defn start-random-chaos!
  "Starts random chaos mode where scenarios are randomly activated and deactivated.

   Options:
   - :min-interval-ms - Minimum time between chaos events (default 30000)
   - :max-interval-ms - Maximum time between chaos events (default 120000)
   - :seed - Random seed"
  [{:keys [min-interval-ms max-interval-ms seed]
    :or {min-interval-ms 30000
         max-interval-ms 120000
         seed (System/currentTimeMillis)}}]
  (when @random-chaos-running
    (throw (ex-info "Random chaos already running" {:type :already-running})))

  (log/warn "CHAOS: Starting random chaos mode")

  (let [rng (Random. seed)
        stop-ch (chan)
        geozones nodes/sample-geohashes]
    (reset! random-chaos-channel stop-ch)
    (reset! random-chaos-running true)

    (go-loop []
      (let [wait-ms (+ min-interval-ms (.nextInt rng (- max-interval-ms min-interval-ms)))
            [_ ch] (alts! [stop-ch (timeout wait-ms)])]
        (if (= ch stop-ch)
          (do
            (deactivate-all-chaos!)
            (log/info "CHAOS: Random chaos mode stopped"))
          (do
            ;; Randomly activate or deactivate a scenario
            (let [scenario (nth [:grid-failure :kafka-partition :xtdb-slowdown]
                                (.nextInt rng 3))
                  target-geozone (nth geozones (.nextInt rng (count geozones)))
                  currently-active? (get-in @chaos-flags [scenario :active])]
              (if currently-active?
                ;; Deactivate
                (case scenario
                  :grid-failure (deactivate-grid-failure!)
                  :kafka-partition (deactivate-kafka-partition!)
                  :xtdb-slowdown (deactivate-xtdb-slowdown!))
                ;; Activate
                (case scenario
                  :grid-failure (activate-grid-failure! target-geozone)
                  :kafka-partition (activate-kafka-partition! target-geozone)
                  :xtdb-slowdown (activate-xtdb-slowdown!))))
            (recur)))))

    stop-ch))

(defn stop-random-chaos!
  "Stops random chaos mode."
  []
  (when-let [ch @random-chaos-channel]
    (close! ch)
    (reset! random-chaos-channel nil))
  (reset! random-chaos-running false))
