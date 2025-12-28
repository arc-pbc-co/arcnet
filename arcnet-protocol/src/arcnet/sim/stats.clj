(ns arcnet.sim.stats
  "Statistics collection and assertions for ARCNet load testing.

   Tracks:
   - Scheduling latency p50/p95/p99
   - Reservation conflict rate
   - Retry rate
   - Rejection rate

   Provides assertions:
   - p99 latency < 100ms under 5k RPS with no chaos
   - System degrades gracefully under chaos (rejections increase, but no crashes)"
  (:require [clojure.tools.logging :as log]
            [arcnet.observability.metrics :as metrics])
  (:import [java.util.concurrent ConcurrentLinkedQueue]
           [java.util.concurrent.atomic AtomicLong AtomicInteger]
           [java.time Instant Duration]
           [io.prometheus.client Histogram Counter Gauge Summary]))

;; =============================================================================
;; Rolling Window Storage
;; =============================================================================

(def ^:const window-size-ms 60000)  ; 1 minute rolling window
(def ^:const max-samples 100000)    ; Max samples to keep

(defrecord LatencySample [^long timestamp-ms ^double latency-ms])

(defonce ^:private latency-samples (ConcurrentLinkedQueue.))
(defonce ^:private conflict-count (AtomicLong. 0))
(defonce ^:private retry-count (AtomicLong. 0))
(defonce ^:private rejection-count (AtomicLong. 0))
(defonce ^:private success-count (AtomicLong. 0))
(defonce ^:private total-requests (AtomicLong. 0))

;; =============================================================================
;; Prometheus Metrics
;; =============================================================================

(defonce scheduling-latency-histogram
  (-> (Histogram/build)
      (.name "sim_scheduling_latency_seconds")
      (.help "Scheduling latency distribution")
      (.buckets (double-array [0.001 0.005 0.01 0.025 0.05 0.075 0.1 0.25 0.5 1.0]))
      (.register (metrics/get-registry))))

(defonce scheduling-latency-summary
  (-> (Summary/build)
      (.name "sim_scheduling_latency_summary")
      (.help "Scheduling latency percentiles")
      (.quantile 0.5 0.01)
      (.quantile 0.95 0.01)
      (.quantile 0.99 0.01)
      (.quantile 0.999 0.01)
      (.register (metrics/get-registry))))

(defonce reservation-conflicts-total
  (-> (Counter/build)
      (.name "sim_reservation_conflicts_total")
      (.help "Total reservation conflicts")
      (.register (metrics/get-registry))))

(defonce scheduling-retries-total
  (-> (Counter/build)
      (.name "sim_scheduling_retries_total")
      (.help "Total scheduling retries")
      (.register (metrics/get-registry))))

(defonce scheduling-rejections-total
  (-> (Counter/build)
      (.name "sim_scheduling_rejections_total")
      (.help "Total scheduling rejections")
      (.register (metrics/get-registry))))

(defonce scheduling-successes-total
  (-> (Counter/build)
      (.name "sim_scheduling_successes_total")
      (.help "Total successful schedulings")
      (.register (metrics/get-registry))))

(defonce current-conflict-rate
  (-> (Gauge/build)
      (.name "sim_current_conflict_rate")
      (.help "Current reservation conflict rate (per second)")
      (.register (metrics/get-registry))))

(defonce current-retry-rate
  (-> (Gauge/build)
      (.name "sim_current_retry_rate")
      (.help "Current retry rate (per second)")
      (.register (metrics/get-registry))))

(defonce current-rejection-rate
  (-> (Gauge/build)
      (.name "sim_current_rejection_rate")
      (.help "Current rejection rate (per second)")
      (.register (metrics/get-registry))))

;; =============================================================================
;; Recording Functions
;; =============================================================================

(defn record-latency!
  "Records a scheduling latency sample."
  [latency-ms]
  (let [now (System/currentTimeMillis)
        sample (->LatencySample now latency-ms)]
    ;; Add to rolling window
    (.offer latency-samples sample)

    ;; Trim old samples
    (while (> (.size latency-samples) max-samples)
      (.poll latency-samples))

    ;; Update Prometheus metrics
    (.observe scheduling-latency-histogram (/ latency-ms 1000.0))
    (.observe scheduling-latency-summary (/ latency-ms 1000.0))))

(defn record-conflict!
  "Records a reservation conflict."
  []
  (.incrementAndGet conflict-count)
  (.inc reservation-conflicts-total))

(defn record-retry!
  "Records a scheduling retry."
  []
  (.incrementAndGet retry-count)
  (.inc scheduling-retries-total))

(defn record-rejection!
  "Records a scheduling rejection."
  []
  (.incrementAndGet rejection-count)
  (.inc scheduling-rejections-total))

(defn record-success!
  "Records a successful scheduling."
  []
  (.incrementAndGet success-count)
  (.inc scheduling-successes-total))

(defn record-request!
  "Records that a request was processed (regardless of outcome)."
  []
  (.incrementAndGet total-requests))

;; =============================================================================
;; Sample Collection
;; =============================================================================

(defn- get-recent-samples
  "Returns samples from the last window-size-ms milliseconds."
  []
  (let [cutoff (- (System/currentTimeMillis) window-size-ms)]
    (->> (iterator-seq (.iterator latency-samples))
         (filter #(> (:timestamp-ms %) cutoff))
         (map :latency-ms)
         (vec))))

(defn- percentile
  "Calculates the nth percentile of a sorted sequence."
  [sorted-values p]
  (if (empty? sorted-values)
    0.0
    (let [n (count sorted-values)
          k (max 0 (min (dec n) (int (* p n))))]
      (nth sorted-values k))))

;; =============================================================================
;; Statistics Calculation
;; =============================================================================

(defn calculate-latency-stats
  "Calculates latency statistics from recent samples."
  []
  (let [samples (get-recent-samples)]
    (if (empty? samples)
      {:count 0
       :min 0.0
       :max 0.0
       :avg 0.0
       :p50 0.0
       :p95 0.0
       :p99 0.0
       :p999 0.0}
      (let [sorted (vec (sort samples))
            n (count sorted)]
        {:count n
         :min (first sorted)
         :max (last sorted)
         :avg (/ (reduce + samples) n)
         :p50 (percentile sorted 0.50)
         :p95 (percentile sorted 0.95)
         :p99 (percentile sorted 0.99)
         :p999 (percentile sorted 0.999)}))))

(defn calculate-rates
  "Calculates current rates (per second) over the window."
  []
  (let [window-seconds (/ window-size-ms 1000.0)
        total (.get total-requests)
        conflicts (.get conflict-count)
        retries (.get retry-count)
        rejections (.get rejection-count)
        successes (.get success-count)]
    {:total-requests total
     :successes successes
     :conflicts conflicts
     :retries retries
     :rejections rejections
     ;; Rates (simple division by window - for accurate rates use windowed counters)
     :conflict-rate (if (pos? total) (/ conflicts (double total)) 0.0)
     :retry-rate (if (pos? total) (/ retries (double total)) 0.0)
     :rejection-rate (if (pos? total) (/ rejections (double total)) 0.0)
     :success-rate (if (pos? total) (/ successes (double total)) 0.0)}))

(defn get-stats
  "Returns comprehensive statistics."
  []
  (let [latency (calculate-latency-stats)
        rates (calculate-rates)]
    {:latency latency
     :rates rates
     :window-ms window-size-ms}))

;; =============================================================================
;; Assertions
;; =============================================================================

(defn assert-latency-p99!
  "Asserts that p99 latency is under the threshold.

   Default threshold is 100ms."
  [& {:keys [threshold-ms] :or {threshold-ms 100}}]
  (let [stats (calculate-latency-stats)
        p99 (:p99 stats)]
    (if (> p99 threshold-ms)
      (do
        (log/error "ASSERTION FAILED: p99 latency exceeds threshold"
                   {:p99 p99 :threshold threshold-ms})
        {:passed false
         :assertion :p99-latency
         :actual p99
         :threshold threshold-ms
         :message (format "p99 latency %.2fms exceeds threshold %.2fms" p99 (double threshold-ms))})
      (do
        (log/info "ASSERTION PASSED: p99 latency within threshold"
                  {:p99 p99 :threshold threshold-ms})
        {:passed true
         :assertion :p99-latency
         :actual p99
         :threshold threshold-ms}))))

(defn assert-rejection-rate!
  "Asserts that rejection rate is under the threshold.

   Default threshold is 5% (0.05)."
  [& {:keys [threshold] :or {threshold 0.05}}]
  (let [rates (calculate-rates)
        rejection-rate (:rejection-rate rates)]
    (if (> rejection-rate threshold)
      (do
        (log/error "ASSERTION FAILED: Rejection rate exceeds threshold"
                   {:rejection-rate rejection-rate :threshold threshold})
        {:passed false
         :assertion :rejection-rate
         :actual rejection-rate
         :threshold threshold
         :message (format "Rejection rate %.2f%% exceeds threshold %.2f%%"
                          (* 100 rejection-rate) (* 100 threshold))})
      (do
        (log/info "ASSERTION PASSED: Rejection rate within threshold"
                  {:rejection-rate rejection-rate :threshold threshold})
        {:passed true
         :assertion :rejection-rate
         :actual rejection-rate
         :threshold threshold}))))

(defn assert-no-crashes!
  "Asserts that no crashes occurred (success + rejection = total).

   This verifies graceful degradation - all requests are either
   successfully scheduled or properly rejected, none are lost."
  []
  (let [rates (calculate-rates)
        total (:total-requests rates)
        accounted (+ (:successes rates) (:rejections rates))]
    ;; Allow some tolerance for in-flight requests
    (if (< accounted (* 0.95 total))
      (do
        (log/error "ASSERTION FAILED: Potential request loss detected"
                   {:total total :accounted accounted})
        {:passed false
         :assertion :no-crashes
         :total total
         :accounted accounted
         :message (format "Only %d of %d requests accounted for" accounted total)})
      (do
        (log/info "ASSERTION PASSED: All requests properly handled"
                  {:total total :accounted accounted})
        {:passed true
         :assertion :no-crashes
         :total total
         :accounted accounted}))))

(defn assert-graceful-degradation!
  "Asserts that system degrades gracefully under chaos.

   Requirements:
   - All requests are accounted for (no crashes)
   - Rejection rate may increase but stays reasonable (<50%)
   - Some successes still occur (system not completely dead)"
  []
  (let [rates (calculate-rates)
        no-crashes (assert-no-crashes!)
        rejection-ok (< (:rejection-rate rates) 0.50)
        some-success (pos? (:successes rates))]
    (if (and (:passed no-crashes) rejection-ok some-success)
      {:passed true
       :assertion :graceful-degradation
       :details {:no-crashes (:passed no-crashes)
                 :rejection-rate (:rejection-rate rates)
                 :successes (:successes rates)}}
      (do
        (log/error "ASSERTION FAILED: System not degrading gracefully"
                   {:no-crashes (:passed no-crashes)
                    :rejection-rate (:rejection-rate rates)
                    :successes (:successes rates)})
        {:passed false
         :assertion :graceful-degradation
         :details {:no-crashes (:passed no-crashes)
                   :rejection-rate (:rejection-rate rates)
                   :successes (:successes rates)}}))))

(defn run-all-assertions!
  "Runs all assertions and returns results."
  [& {:keys [chaos-active?] :or {chaos-active? false}}]
  (let [results (if chaos-active?
                  ;; Under chaos: verify graceful degradation
                  {:graceful-degradation (assert-graceful-degradation!)}
                  ;; No chaos: verify tight SLAs
                  {:p99-latency (assert-latency-p99!)
                   :rejection-rate (assert-rejection-rate!)
                   :no-crashes (assert-no-crashes!)})]
    {:all-passed (every? :passed (vals results))
     :results results}))

;; =============================================================================
;; Reset
;; =============================================================================

(defn reset-stats!
  "Resets all statistics counters."
  []
  (.clear latency-samples)
  (.set conflict-count 0)
  (.set retry-count 0)
  (.set rejection-count 0)
  (.set success-count 0)
  (.set total-requests 0)
  (log/info "Statistics reset"))

;; =============================================================================
;; Periodic Metrics Update
;; =============================================================================

(defonce ^:private metrics-updater-running (atom false))
(defonce ^:private metrics-updater-channel (atom nil))

(defn start-metrics-updater!
  "Starts periodic metrics gauge updates."
  [& {:keys [interval-ms] :or {interval-ms 1000}}]
  (when @metrics-updater-running
    (throw (ex-info "Metrics updater already running" {})))

  (let [stop-ch (clojure.core.async/chan)]
    (reset! metrics-updater-channel stop-ch)
    (reset! metrics-updater-running true)

    (clojure.core.async/go-loop []
      (let [[_ ch] (clojure.core.async/alts! [stop-ch (clojure.core.async/timeout interval-ms)])]
        (when-not (= ch stop-ch)
          (let [rates (calculate-rates)]
            (.set current-conflict-rate (:conflict-rate rates))
            (.set current-retry-rate (:retry-rate rates))
            (.set current-rejection-rate (:rejection-rate rates)))
          (recur))))

    stop-ch))

(defn stop-metrics-updater!
  "Stops periodic metrics updates."
  []
  (when-let [ch @metrics-updater-channel]
    (clojure.core.async/close! ch)
    (reset! metrics-updater-channel nil))
  (reset! metrics-updater-running false))

;; =============================================================================
;; Report Generation
;; =============================================================================

(defn generate-report
  "Generates a comprehensive test report."
  []
  (let [stats (get-stats)
        latency (:latency stats)
        rates (:rates stats)]
    {:timestamp (Instant/now)
     :summary {:total-requests (:total-requests rates)
               :successes (:successes rates)
               :rejections (:rejections rates)
               :success-rate (* 100 (:success-rate rates))
               :rejection-rate (* 100 (:rejection-rate rates))}
     :latency {:samples (:count latency)
               :min-ms (:min latency)
               :max-ms (:max latency)
               :avg-ms (:avg latency)
               :p50-ms (:p50 latency)
               :p95-ms (:p95 latency)
               :p99-ms (:p99 latency)
               :p999-ms (:p999 latency)}
     :conflicts {:total (:conflicts rates)
                 :rate (* 100 (:conflict-rate rates))}
     :retries {:total (:retries rates)
               :rate (* 100 (:retry-rate rates))}}))

(defn print-report!
  "Prints a formatted test report."
  []
  (let [report (generate-report)]
    (println "\n==================== SIMULATION REPORT ====================")
    (println (format "Timestamp: %s" (:timestamp report)))
    (println "\n--- Summary ---")
    (println (format "Total Requests:  %,d" (get-in report [:summary :total-requests])))
    (println (format "Successes:       %,d (%.2f%%)"
                     (get-in report [:summary :successes])
                     (get-in report [:summary :success-rate])))
    (println (format "Rejections:      %,d (%.2f%%)"
                     (get-in report [:summary :rejections])
                     (get-in report [:summary :rejection-rate])))
    (println "\n--- Latency (ms) ---")
    (println (format "Samples:  %,d" (get-in report [:latency :samples])))
    (println (format "Min:      %.2f" (get-in report [:latency :min-ms])))
    (println (format "Max:      %.2f" (get-in report [:latency :max-ms])))
    (println (format "Avg:      %.2f" (get-in report [:latency :avg-ms])))
    (println (format "p50:      %.2f" (get-in report [:latency :p50-ms])))
    (println (format "p95:      %.2f" (get-in report [:latency :p95-ms])))
    (println (format "p99:      %.2f" (get-in report [:latency :p99-ms])))
    (println (format "p99.9:    %.2f" (get-in report [:latency :p999-ms])))
    (println "\n--- Conflicts & Retries ---")
    (println (format "Conflicts: %,d (%.2f%%)"
                     (get-in report [:conflicts :total])
                     (get-in report [:conflicts :rate])))
    (println (format "Retries:   %,d (%.2f%%)"
                     (get-in report [:retries :total])
                     (get-in report [:retries :rate])))
    (println "============================================================\n")
    report))
