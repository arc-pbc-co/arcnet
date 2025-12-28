(ns arcnet.scheduler.rules
  "Scheduling rules for candidate selection and scoring.

   Provides:
   - Candidate node filtering based on availability criteria
   - Scoring function for ranking nodes
   - Geohash-based latency estimation

   Scoring factors:
   - +1.0 for solar energy (green computing preference)
   - +0.5 for battery > 80%
   - -0.2 per 10ms estimated network latency
   - -(gpu-utilization) to prefer less loaded nodes"
  (:require [clojure.tools.logging :as log]
            [arcnet.state.regional :as regional]
            [arcnet.scheduler.reservation :as reservation]
            [arcnet.observability.metrics :as metrics]
            [arcnet.observability.tracing :as tracing])
  (:import [java.time Instant Duration]
           [java.util UUID]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const max-gpu-utilization 0.85)
(def ^:const staleness-threshold-seconds 30)

;; Scoring weights
(def ^:const solar-bonus 1.0)
(def ^:const high-battery-bonus 0.5)
(def ^:const high-battery-threshold 0.8)
(def ^:const latency-penalty-per-10ms 0.2)

;; Geohash distance estimation (approximate km per character difference)
(def ^:const geohash-precision-km
  {1 5000   ; ~5000 km
   2 1250   ; ~1250 km
   3 156    ; ~156 km
   4 39     ; ~39 km
   5 5      ; ~5 km
   6 1.2})  ; ~1.2 km

;; =============================================================================
;; Geohash Distance Heuristic
;; =============================================================================

(defn common-prefix-length
  "Returns the length of the common prefix between two strings."
  [s1 s2]
  (let [min-len (min (count s1) (count s2))]
    (loop [i 0]
      (if (or (>= i min-len)
              (not= (nth s1 i) (nth s2 i)))
        i
        (recur (inc i))))))

(defn estimate-distance-km
  "Estimates distance in km between two geohashes.

   Uses the common prefix length to estimate distance.
   Longer common prefix = closer proximity."
  [geohash1 geohash2]
  (when (and geohash1 geohash2)
    (let [prefix-len (common-prefix-length geohash1 geohash2)]
      (if (zero? prefix-len)
        ;; No common prefix - maximum distance
        (get geohash-precision-km 1 5000)
        ;; Use precision of the common prefix
        (get geohash-precision-km (min prefix-len 6) 1.2)))))

(defn estimate-latency-ms
  "Estimates network latency in ms based on geohash distance.

   Rough heuristic: ~0.1ms per km (accounting for routing overhead)."
  [geohash1 geohash2]
  (let [distance-km (estimate-distance-km geohash1 geohash2)]
    ;; Minimum 1ms, plus ~0.1ms per km
    (+ 1.0 (* 0.1 distance-km))))

;; =============================================================================
;; Candidate Filtering
;; =============================================================================

(defn- node-not-stale?
  "Returns true if the node was seen within the staleness threshold."
  [node]
  (when-let [last-seen (:node/last-seen node)]
    (let [last-seen-instant (if (instance? Instant last-seen)
                              last-seen
                              (.toInstant last-seen))
          cutoff (.minus (Instant/now)
                         (Duration/ofSeconds staleness-threshold-seconds))]
      (.isAfter last-seen-instant cutoff))))

(defn- node-has-model?
  "Returns true if the node has the specified model loaded."
  [node model-id]
  (let [models (or (:node/models-loaded node) [])]
    (some #(= model-id %) models)))

(defn- node-gpu-available?
  "Returns true if the node's GPU utilization is below threshold."
  [node]
  (let [util (or (:node/gpu-utilization node) 1.0)]
    (< util max-gpu-utilization)))

(defn- node-not-reserved?
  "Returns true if the node has no active reservation."
  [node]
  (let [reservation (:node/reservation node)]
    (not (reservation/reservation-active? reservation))))

(defn candidate-nodes
  "Finds all candidate nodes for an inference request.

   Filters by:
   - Model availability (has required model loaded)
   - GPU utilization < 85%
   - Not stale (seen within 30 seconds)
   - No active reservation

   Parameters:
   - model-id: Required model ID
   - requester-geohash: Geohash of the requester (for latency estimation)

   Returns a sequence of candidate node documents."
  [model-id requester-geohash]
  {:pre [(string? model-id)]}
  (tracing/with-span {:name "candidate-nodes"
                      :attributes {:model-id model-id
                                   :requester-geohash requester-geohash}}
    (let [timer (metrics/start-timer)
          xtdb (regional/get-node)
          ;; Query all nodes with the model loaded
          ;; Note: XTDB v2 SQL doesn't support array contains directly,
          ;; so we fetch all and filter in Clojure for model matching
          staleness-cutoff (.minus (Instant/now)
                                   (Duration/ofSeconds staleness-threshold-seconds))
          sql "SELECT *
               FROM nodes
               WHERE node_gpu_utilization < ?
                 AND node_last_seen >= ?"
          params [max-gpu-utilization (.toString staleness-cutoff)]
          results (.query xtdb sql (object-array params))
          all-candidates (into [] (iterator-seq (.iterator results)))
          ;; Apply remaining filters
          filtered (->> all-candidates
                        (filter #(node-has-model? % model-id))
                        (filter node-not-reserved?))]
      (metrics/record-operation!
       {:operation "candidate-nodes"
        :duration-ms (timer)
        :success? true})
      (log/debug "Found candidate nodes"
                 {:model-id model-id
                  :total-queried (count all-candidates)
                  :after-filters (count filtered)})
      filtered)))

;; =============================================================================
;; Scoring Function
;; =============================================================================

(defn score-node
  "Scores a candidate node for scheduling priority.

   Scoring factors:
   - +1.0 if energy-source is solar
   - +0.5 if battery > 0.8
   - -0.2 per 10ms of estimated network latency
   - -(gpu-utilization) to prefer less loaded nodes

   Parameters:
   - node: Node document
   - requester-geohash: Geohash of the requester

   Returns a float score (higher is better)."
  [node requester-geohash]
  (let [energy-source (:node/energy-source node)
        battery-level (or (:node/battery-level node) 0.0)
        gpu-utilization (or (:node/gpu-utilization node) 0.0)
        node-geohash (:node/geohash node)
        ;; Calculate individual scores
        solar-score (if (= :solar energy-source) solar-bonus 0.0)
        battery-score (if (> battery-level high-battery-threshold)
                        high-battery-bonus
                        0.0)
        ;; Latency penalty
        latency-ms (if (and requester-geohash node-geohash)
                     (estimate-latency-ms requester-geohash node-geohash)
                     50.0)  ; Default 50ms if geohash unavailable
        latency-score (* -1.0 latency-penalty-per-10ms (/ latency-ms 10.0))
        ;; GPU utilization penalty (prefer less loaded)
        gpu-score (* -1.0 gpu-utilization)
        ;; Total score
        total-score (+ solar-score battery-score latency-score gpu-score)]
    (log/trace "Scored node"
               {:node-id (:xt/id node)
                :solar-score solar-score
                :battery-score battery-score
                :latency-score latency-score
                :gpu-score gpu-score
                :total total-score})
    total-score))

(defn score-candidates
  "Scores all candidate nodes and returns them sorted by score (descending).

   Parameters:
   - candidates: Sequence of node documents
   - requester-geohash: Geohash of the requester

   Returns a sequence of {:node node :score score} maps, sorted highest first."
  [candidates requester-geohash]
  (->> candidates
       (map (fn [node]
              {:node node
               :score (score-node node requester-geohash)}))
       (sort-by :score >)))

;; =============================================================================
;; Selection
;; =============================================================================

(defn select-best-candidate
  "Selects the highest-scoring candidate node.

   Parameters:
   - model-id: Required model ID
   - requester-geohash: Geohash of the requester

   Returns {:node node :score score} or nil if no candidates."
  [model-id requester-geohash]
  (tracing/with-span {:name "select-best-candidate"
                      :attributes {:model-id model-id}}
    (let [candidates (candidate-nodes model-id requester-geohash)
          scored (score-candidates candidates requester-geohash)]
      (when-let [best (first scored)]
        (log/debug "Selected best candidate"
                   {:node-id (get-in best [:node :xt/id])
                    :score (:score best)
                    :model-id model-id})
        best))))

(defn select-top-candidates
  "Selects the top N scoring candidate nodes.

   Useful for fallback attempts if the best candidate fails.

   Parameters:
   - model-id: Required model ID
   - requester-geohash: Geohash of the requester
   - n: Number of candidates to return

   Returns a sequence of {:node node :score score} maps."
  [model-id requester-geohash n]
  (let [candidates (candidate-nodes model-id requester-geohash)
        scored (score-candidates candidates requester-geohash)]
    (take n scored)))

;; =============================================================================
;; Detailed Scoring Report (for debugging/monitoring)
;; =============================================================================

(defn score-breakdown
  "Returns a detailed breakdown of a node's score.

   Useful for debugging and monitoring scheduling decisions."
  [node requester-geohash]
  (let [energy-source (:node/energy-source node)
        battery-level (or (:node/battery-level node) 0.0)
        gpu-utilization (or (:node/gpu-utilization node) 0.0)
        node-geohash (:node/geohash node)
        latency-ms (if (and requester-geohash node-geohash)
                     (estimate-latency-ms requester-geohash node-geohash)
                     50.0)
        solar-score (if (= :solar energy-source) solar-bonus 0.0)
        battery-score (if (> battery-level high-battery-threshold)
                        high-battery-bonus
                        0.0)
        latency-score (* -1.0 latency-penalty-per-10ms (/ latency-ms 10.0))
        gpu-score (* -1.0 gpu-utilization)]
    {:node-id (:xt/id node)
     :node-geohash node-geohash
     :requester-geohash requester-geohash
     :factors {:energy-source energy-source
               :battery-level battery-level
               :gpu-utilization gpu-utilization
               :estimated-latency-ms latency-ms}
     :scores {:solar solar-score
              :battery battery-score
              :latency latency-score
              :gpu gpu-score}
     :total (+ solar-score battery-score latency-score gpu-score)}))

;; =============================================================================
;; Rules Summary
;; =============================================================================

(def scheduling-rules
  "Documentation of the scheduling rules.

   Candidate Criteria:
   1. Model must be loaded on the node
   2. GPU utilization must be < 85%
   3. Node must have been seen within last 30 seconds
   4. Node must not have an active reservation

   Scoring (higher is better):
   1. +1.0 for solar energy source
   2. +0.5 for battery level > 80%
   3. -0.2 per 10ms of estimated network latency
   4. -(gpu-utilization) to prefer less loaded nodes

   Selection:
   - Candidates are sorted by score descending
   - Highest scoring node is selected
   - If selection fails, next best is tried"
  {:candidate-criteria [:model-loaded
                        :gpu-available
                        :not-stale
                        :not-reserved]
   :scoring-factors {:solar-bonus solar-bonus
                     :high-battery-bonus high-battery-bonus
                     :high-battery-threshold high-battery-threshold
                     :latency-penalty-per-10ms latency-penalty-per-10ms}
   :thresholds {:max-gpu-utilization max-gpu-utilization
                :staleness-threshold-seconds staleness-threshold-seconds}})
