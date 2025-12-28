(ns arcnet.sim.nodes
  "Node simulation for ARCNet load testing.

   Generates realistic node behavior:
   - 1,000 nodes across 20 geozones
   - Solar curve: battery charges 6am-6pm, drains overnight
   - GPU utilization follows random walk with mean reversion
   - 5% of nodes randomly go stale each hour

   All nodes are in-memory simulated entities that produce
   telemetry to the state layer."
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go go-loop <! >! chan close!
                                                   timeout alts!]]
            [arcnet.schema.registry :as schema]
            [arcnet.observability.metrics :as metrics])
  (:import [java.time LocalTime ZonedDateTime ZoneId]
           [java.util UUID Random]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const default-node-count 1000)
(def ^:const default-geozone-count 20)
(def ^:const telemetry-interval-ms 5000)
(def ^:const stale-probability-per-hour 0.05)

;; GPU utilization random walk parameters
(def ^:const gpu-mean 0.5)
(def ^:const gpu-mean-reversion-rate 0.1)
(def ^:const gpu-volatility 0.15)

;; Battery parameters
(def ^:const battery-charge-rate-per-hour 0.15)  ; 15% per hour during daylight
(def ^:const battery-drain-rate-per-hour 0.08)   ; 8% per hour at night
(def ^:const min-battery-level 0.05)

;; =============================================================================
;; Geozones
;; =============================================================================

(def sample-geohashes
  "20 sample geohashes representing different geozones (6-char precision).
   These represent locations across North America."
  ["9q8yyk"  ; San Francisco
   "9q5ctr"  ; Los Angeles
   "9xj64r"  ; Denver
   "dp3wjz"  ; Chicago
   "drt2yz"  ; New York
   "djt4ry"  ; Atlanta
   "9v6kn8"  ; Dallas
   "c23nb6"  ; Seattle
   "dn5b9w"  ; Miami
   "9yzgnk"  ; Phoenix
   "dnh0pg"  ; Boston
   "dpwc5g"  ; Philadelphia
   "dpm8dq"  ; Washington DC
   "dr5r7p"  ; Baltimore
   "9qh0k9"  ; San Diego
   "9r2sdy"  ; Portland
   "cbp3b2"  ; Vancouver
   "f244mc"  ; Montreal
   "dpz87z"  ; Toronto
   "9tbqnx"  ; Houston
   ])

;; =============================================================================
;; Model Distribution
;; =============================================================================

(def common-models
  "Models commonly loaded on nodes, ordered by popularity."
  ["llama-3.1-8b"
   "llama-3.1-70b"
   "mistral-7b"
   "codellama-13b"
   "phi-3-mini"
   "gemma-7b"
   "qwen-7b"
   "llama-2-13b"
   "starcoder-15b"
   "falcon-7b"])

(defn- zipf-distribution
  "Returns an index from 0 to n-1 following Zipf's law."
  [^Random rng n]
  (let [harmonic-sum (reduce + (map #(/ 1.0 (inc %)) (range n)))
        target (* (.nextDouble rng) harmonic-sum)]
    (loop [sum 0.0
           k 0]
      (let [new-sum (+ sum (/ 1.0 (inc k)))]
        (if (or (>= new-sum target) (>= k (dec n)))
          k
          (recur new-sum (inc k)))))))

(defn- select-models
  "Selects 1-3 models for a node using Zipf distribution."
  [^Random rng]
  (let [count (inc (.nextInt rng 3))]
    (vec (distinct (repeatedly count #(nth common-models (zipf-distribution rng (count common-models))))))))

;; =============================================================================
;; Solar Curve Simulation
;; =============================================================================

(defn solar-factor
  "Returns a solar production factor (0.0 to 1.0) based on time of day.

   Peak solar is 1.0 at noon, 0.0 from 6pm to 6am.
   Uses a sine curve for realistic solar panel output."
  [^ZonedDateTime time]
  (let [hour (.getHour time)
        minute (.getMinute time)
        decimal-hour (+ hour (/ minute 60.0))]
    (cond
      ;; Night: 6pm to 6am
      (or (>= decimal-hour 18.0) (< decimal-hour 6.0))
      0.0

      ;; Day: 6am to 6pm
      ;; Map 6am-6pm to 0-π for sine curve
      :else
      (let [normalized (/ (- decimal-hour 6.0) 12.0)  ; 0.0 to 1.0
            angle (* Math/PI normalized)]
        (Math/sin angle)))))

(defn- update-battery-level
  "Updates battery level based on energy source and time of day."
  [current-level energy-source ^ZonedDateTime time delta-hours]
  (let [solar (solar-factor time)]
    (cond
      ;; Solar nodes: charge during day, drain at night
      (= energy-source :solar)
      (if (pos? solar)
        (min 1.0 (+ current-level (* battery-charge-rate-per-hour delta-hours solar)))
        (max min-battery-level (- current-level (* battery-drain-rate-per-hour delta-hours))))

      ;; Grid nodes: always fully charged
      (= energy-source :grid)
      1.0

      ;; Battery nodes: slowly drain
      (= energy-source :battery)
      (max min-battery-level (- current-level (* battery-drain-rate-per-hour delta-hours)))

      :else current-level)))

;; =============================================================================
;; GPU Random Walk
;; =============================================================================

(defn- update-gpu-utilization
  "Updates GPU utilization using Ornstein-Uhlenbeck process (mean-reverting random walk)."
  [^Random rng current-util delta-seconds]
  (let [dt (/ delta-seconds 3600.0)  ; Convert to hours for scaling
        drift (* gpu-mean-reversion-rate (- gpu-mean current-util) dt)
        diffusion (* gpu-volatility (Math/sqrt dt) (.nextGaussian rng))
        new-util (+ current-util drift diffusion)]
    ;; Clamp to valid range
    (max 0.0 (min 0.99 new-util))))

;; =============================================================================
;; Node State
;; =============================================================================

(defrecord SimulatedNode
    [^UUID id
     ^String geohash
     energy-source
     ^double battery-level
     ^double gpu-utilization
     ^double gpu-memory-free-gb
     models-loaded
     ^boolean stale?
     ^long last-telemetry-ms
     ^Random rng])

(defn- create-node
  "Creates a new simulated node."
  [^Random master-rng geohash-idx]
  (let [rng (Random. (.nextLong master-rng))
        geohash (nth sample-geohashes (mod geohash-idx (count sample-geohashes)))
        energy-source (let [r (.nextDouble rng)]
                        (cond
                          (< r 0.40) :solar    ; 40% solar
                          (< r 0.75) :grid     ; 35% grid
                          :else :battery))     ; 25% battery
        gpu-memory (+ 16.0 (* 64.0 (.nextDouble rng)))]  ; 16-80 GB
    (->SimulatedNode
     (UUID/randomUUID)
     geohash
     energy-source
     (if (= energy-source :grid) 1.0 (+ 0.3 (* 0.7 (.nextDouble rng))))
     (+ 0.2 (* 0.6 (.nextDouble rng)))  ; Initial utilization 20-80%
     (* gpu-memory (+ 0.3 (* 0.5 (.nextDouble rng))))  ; 30-80% free
     (select-models rng)
     false
     (System/currentTimeMillis)
     rng)))

(defn- should-go-stale?
  "Determines if a node should go stale this tick.

   5% probability per hour = 5%/3600 per second."
  [^Random rng delta-seconds]
  (let [prob-per-tick (* stale-probability-per-hour (/ delta-seconds 3600.0))]
    (< (.nextDouble rng) prob-per-tick)))

(defn- should-recover?
  "Stale nodes have 10% chance per minute to recover."
  [^Random rng delta-seconds]
  (let [prob-per-tick (* 0.10 (/ delta-seconds 60.0))]
    (< (.nextDouble rng) prob-per-tick)))

(defn update-node
  "Updates a simulated node's state for one time step."
  [^SimulatedNode node ^ZonedDateTime now delta-seconds]
  (let [rng (:rng node)
        delta-hours (/ delta-seconds 3600.0)]
    (if (:stale? node)
      ;; Stale node might recover
      (if (should-recover? rng delta-seconds)
        (assoc node
               :stale? false
               :last-telemetry-ms (System/currentTimeMillis))
        node)
      ;; Active node
      (let [new-battery (update-battery-level (:battery-level node)
                                               (:energy-source node)
                                               now
                                               delta-hours)
            new-gpu-util (update-gpu-utilization rng (:gpu-utilization node) delta-seconds)
            now-stale? (or (should-go-stale? rng delta-seconds)
                           ;; Also go stale if battery critically low
                           (and (not= :grid (:energy-source node))
                                (< new-battery 0.1)
                                (< (.nextDouble rng) 0.3)))]
        (assoc node
               :battery-level new-battery
               :gpu-utilization new-gpu-util
               :stale? now-stale?
               :last-telemetry-ms (if now-stale?
                                    (:last-telemetry-ms node)
                                    (System/currentTimeMillis)))))))

;; =============================================================================
;; Node Fleet
;; =============================================================================

(defonce ^:private fleet-state (atom nil))
(defonce ^:private fleet-running (atom false))
(defonce ^:private fleet-channel (atom nil))

(defn- create-fleet
  "Creates a fleet of simulated nodes."
  [node-count seed]
  (let [master-rng (Random. seed)]
    (vec (for [i (range node-count)]
           (create-node master-rng i)))))

(defn- fleet-tick!
  "Updates all nodes in the fleet for one time step."
  [delta-seconds]
  (let [now (ZonedDateTime/now (ZoneId/of "UTC"))]
    (swap! fleet-state
           (fn [nodes]
             (mapv #(update-node % now delta-seconds) nodes)))))

(defn get-active-nodes
  "Returns all non-stale nodes in the fleet."
  []
  (filter #(not (:stale? %)) @fleet-state))

(defn get-node-by-id
  "Returns a node by its ID."
  [node-id]
  (first (filter #(= node-id (:id %)) @fleet-state)))

(defn get-nodes-in-geozone
  "Returns all nodes in a geozone (matching geohash prefix)."
  [geohash-prefix]
  (filter #(clojure.string/starts-with? (:geohash %) geohash-prefix)
          @fleet-state))

(defn get-fleet-stats
  "Returns current fleet statistics."
  []
  (when-let [nodes @fleet-state]
    (let [active (filter #(not (:stale? %)) nodes)
          by-energy (group-by :energy-source nodes)]
      {:total-nodes (count nodes)
       :active-nodes (count active)
       :stale-nodes (- (count nodes) (count active))
       :stale-percentage (* 100.0 (/ (- (count nodes) (count active)) (count nodes)))
       :solar-nodes (count (:solar by-energy))
       :grid-nodes (count (:grid by-energy))
       :battery-nodes (count (:battery by-energy))
       :avg-battery-level (if (seq active)
                            (/ (reduce + (map :battery-level active)) (count active))
                            0.0)
       :avg-gpu-utilization (if (seq active)
                              (/ (reduce + (map :gpu-utilization active)) (count active))
                              0.0)
       :geozones (count (distinct (map :geohash nodes)))})))

;; =============================================================================
;; Telemetry Generation
;; =============================================================================

(defn node->telemetry
  "Converts a SimulatedNode to a NodeTelemetry entity."
  [^SimulatedNode node]
  {:schema/version 2
   :id (:id node)
   :timestamp (java.util.Date.)
   :geohash (:geohash node)
   :energy-source (:energy-source node)
   :battery-level (:battery-level node)
   :gpu-utilization (:gpu-utilization node)
   :gpu-memory-free-gb (:gpu-memory-free-gb node)
   :models-loaded (:models-loaded node)})

(defn generate-telemetry-batch
  "Generates telemetry for all active nodes."
  []
  (mapv node->telemetry (get-active-nodes)))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn start!
  "Starts the node simulation.

   Options:
   - :node-count - Number of nodes (default 1000)
   - :seed - Random seed for reproducibility
   - :tick-interval-ms - Update interval (default 1000)
   - :on-telemetry - Callback for telemetry batches"
  [{:keys [node-count seed tick-interval-ms on-telemetry]
    :or {node-count default-node-count
         seed (System/currentTimeMillis)
         tick-interval-ms 1000}}]
  (when @fleet-running
    (throw (ex-info "Fleet simulation already running" {:type :already-running})))

  (log/info "Starting node simulation"
            {:node-count node-count
             :seed seed
             :tick-interval-ms tick-interval-ms})

  ;; Initialize fleet
  (reset! fleet-state (create-fleet node-count seed))

  ;; Start simulation loop
  (let [stop-ch (chan)]
    (reset! fleet-channel stop-ch)
    (reset! fleet-running true)

    (go-loop [last-tick-ms (System/currentTimeMillis)
              telemetry-countdown-ms telemetry-interval-ms]
      (let [[_ ch] (alts! [stop-ch (timeout tick-interval-ms)])]
        (if (= ch stop-ch)
          (log/info "Node simulation stopped")
          (let [now-ms (System/currentTimeMillis)
                delta-ms (- now-ms last-tick-ms)
                delta-seconds (/ delta-ms 1000.0)
                new-countdown (- telemetry-countdown-ms delta-ms)]
            ;; Update fleet
            (fleet-tick! delta-seconds)

            ;; Generate telemetry if interval elapsed
            (when (<= new-countdown 0)
              (when on-telemetry
                (try
                  (on-telemetry (generate-telemetry-batch))
                  (catch Exception e
                    (log/error e "Error in telemetry callback")))))

            (recur now-ms
                   (if (<= new-countdown 0)
                     telemetry-interval-ms
                     new-countdown))))))

    (log/info "Node simulation started" {:stats (get-fleet-stats)})
    stop-ch))

(defn stop!
  "Stops the node simulation."
  []
  (log/info "Stopping node simulation")
  (when-let [ch @fleet-channel]
    (close! ch)
    (reset! fleet-channel nil))
  (reset! fleet-running false)
  (log/info "Node simulation stopped"))

(defn reset!
  "Resets the fleet to initial state."
  [{:keys [node-count seed]
    :or {node-count default-node-count
         seed (System/currentTimeMillis)}}]
  (reset! fleet-state (create-fleet node-count seed)))

;; =============================================================================
;; Chaos Injection
;; =============================================================================

(defn inject-grid-failure!
  "Simulates a grid failure in a geozone.

   - 30% of nodes switch to battery
   - 10% go offline (stale)"
  [geohash-prefix]
  (log/warn "Injecting grid failure" {:geozone geohash-prefix})
  (swap! fleet-state
         (fn [nodes]
           (mapv (fn [node]
                   (if (clojure.string/starts-with? (:geohash node) geohash-prefix)
                     (let [rng (:rng node)
                           r (.nextDouble rng)]
                       (cond
                         ;; 10% go offline
                         (< r 0.10)
                         (assoc node :stale? true)

                         ;; 30% switch to battery (if was grid)
                         (and (< r 0.40) (= :grid (:energy-source node)))
                         (assoc node
                                :energy-source :battery
                                :battery-level 0.8)

                         :else node))
                     node))
                 nodes))))

(defn recover-grid!
  "Recovers nodes from grid failure."
  [geohash-prefix]
  (log/info "Recovering grid" {:geozone geohash-prefix})
  (swap! fleet-state
         (fn [nodes]
           (mapv (fn [node]
                   (if (clojure.string/starts-with? (:geohash node) geohash-prefix)
                     (assoc node
                            :stale? false
                            :energy-source (if (= :battery (:energy-source node))
                                             :grid
                                             (:energy-source node)))
                     node))
                 nodes))))

;; =============================================================================
;; Status
;; =============================================================================

(defn status
  "Returns simulation status."
  []
  {:running @fleet-running
   :stats (get-fleet-stats)})
