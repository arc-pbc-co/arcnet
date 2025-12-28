(ns arcnet.sim.load
  "Load generation for ARCNet stress testing.

   Generates InferenceRequest streams with:
   - Configurable RPS (1k, 5k, 10k)
   - Realistic priority distribution: 70% background, 25% normal, 5% critical
   - Model-id follows Zipf distribution (few models get most requests)

   Uses a token bucket algorithm for precise rate limiting."
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go go-loop <! >! chan close!
                                                   timeout alts! >!!]]
            [arcnet.schema.registry :as schema]
            [arcnet.sim.nodes :as nodes]
            [arcnet.observability.metrics :as metrics])
  (:import [java.util UUID Random]
           [java.util.concurrent.atomic AtomicLong AtomicBoolean]
           [java.util.concurrent Executors TimeUnit ScheduledExecutorService]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const default-rps 1000)
(def ^:const batch-size 100)  ; Requests per batch for efficiency

;; Priority distribution
(def ^:const background-probability 0.70)
(def ^:const normal-probability 0.25)
(def ^:const critical-probability 0.05)

;; Context window distribution parameters
(def ^:const min-context-tokens 128)
(def ^:const max-context-tokens 32768)

;; Max latency by priority (ms)
(def latency-by-priority
  {:critical 100
   :normal 500
   :background 2000})

;; =============================================================================
;; Zipf Distribution for Model Selection
;; =============================================================================

(def model-ids
  "Models ordered by popularity (most popular first)."
  ["llama-3.1-8b"      ; Most popular
   "llama-3.1-70b"
   "mistral-7b"
   "codellama-13b"
   "phi-3-mini"
   "gemma-7b"
   "qwen-7b"
   "llama-2-13b"
   "starcoder-15b"
   "falcon-7b"])        ; Least popular

(defn- zipf-index
  "Returns an index from 0 to n-1 following Zipf's law.

   Zipf parameter s=1.0 (standard Zipf distribution)."
  [^Random rng n]
  (let [harmonic-sum (reduce + (map #(/ 1.0 (inc %)) (range n)))
        target (* (.nextDouble rng) harmonic-sum)]
    (loop [sum 0.0
           k 0]
      (let [new-sum (+ sum (/ 1.0 (inc k)))]
        (if (or (>= new-sum target) (>= k (dec n)))
          k
          (recur new-sum (inc k)))))))

(defn- select-model
  "Selects a model using Zipf distribution."
  [^Random rng]
  (nth model-ids (zipf-index rng (count model-ids))))

;; =============================================================================
;; Priority Selection
;; =============================================================================

(defn- select-priority
  "Selects priority based on distribution: 70% background, 25% normal, 5% critical."
  [^Random rng]
  (let [r (.nextDouble rng)]
    (cond
      (< r critical-probability) :critical
      (< r (+ critical-probability normal-probability)) :normal
      :else :background)))

;; =============================================================================
;; Request Generation
;; =============================================================================

(defn- generate-context-tokens
  "Generates context window size with log-normal distribution.

   This gives realistic distribution where most requests are small
   but occasional large context windows occur."
  [^Random rng]
  (let [;; Log-normal parameters
        mu 7.0    ; Mean of ln(x) ~ 1024 tokens
        sigma 1.5 ; Standard deviation
        log-normal (Math/exp (+ mu (* sigma (.nextGaussian rng))))
        clamped (-> log-normal
                    (max min-context-tokens)
                    (min max-context-tokens))]
    (int clamped)))

(defn- select-requester-geozone
  "Selects a requester geozone from available geozones."
  [^Random rng]
  (nth nodes/sample-geohashes (.nextInt rng (count nodes/sample-geohashes))))

(defn generate-request
  "Generates a single InferenceRequest."
  [^Random rng]
  (let [priority (select-priority rng)
        model-id (select-model rng)]
    {:schema/version 2
     :id (UUID/randomUUID)
     :model-id model-id
     :context-window-tokens (generate-context-tokens rng)
     :priority priority
     :max-latency-ms (get latency-by-priority priority)
     :requester-geozone (select-requester-geozone rng)}))

(defn generate-request-batch
  "Generates a batch of requests."
  [^Random rng batch-size]
  (vec (repeatedly batch-size #(generate-request rng))))

;; =============================================================================
;; Token Bucket Rate Limiter
;; =============================================================================

(defrecord TokenBucket [^AtomicLong tokens
                        ^AtomicLong last-refill-ns
                        ^double tokens-per-ns
                        ^long max-tokens])

(defn- create-token-bucket
  "Creates a token bucket for rate limiting."
  [rps]
  (let [tokens-per-ns (/ rps 1e9)]  ; Convert RPS to tokens per nanosecond
    (->TokenBucket
     (AtomicLong. rps)          ; Start with 1 second worth of tokens
     (AtomicLong. (System/nanoTime))
     tokens-per-ns
     (* rps 2))))               ; Allow up to 2 seconds burst

(defn- acquire-tokens!
  "Attempts to acquire n tokens. Returns true if successful."
  [^TokenBucket bucket n]
  (let [now (System/nanoTime)
        last-refill (.get ^AtomicLong (:last-refill-ns bucket))
        elapsed (- now last-refill)
        refill-amount (long (* elapsed (:tokens-per-ns bucket)))
        ^AtomicLong tokens-atom (:tokens bucket)]
    ;; Try to refill
    (when (pos? refill-amount)
      (when (.compareAndSet ^AtomicLong (:last-refill-ns bucket) last-refill now)
        (loop []
          (let [current (.get tokens-atom)
                new-val (min (:max-tokens bucket) (+ current refill-amount))]
            (when-not (.compareAndSet tokens-atom current new-val)
              (recur))))))
    ;; Try to consume
    (loop []
      (let [current (.get tokens-atom)]
        (if (>= current n)
          (if (.compareAndSet tokens-atom current (- current n))
            true
            (recur))
          false)))))

;; =============================================================================
;; Load Generator State
;; =============================================================================

(defonce ^:private generator-running (atom false))
(defonce ^:private generator-channel (atom nil))
(defonce ^:private generator-executor (atom nil))
(defonce ^:private requests-generated (atom 0))
(defonce ^:private current-rps (atom 0))

;; =============================================================================
;; Metrics
;; =============================================================================

(defonce requests-generated-counter
  (-> (io.prometheus.client.Counter/build)
      (.name "sim_requests_generated_total")
      (.help "Total requests generated by load simulator")
      (.labelNames (into-array String ["priority" "model"]))
      (.register (metrics/get-registry))))

(defonce generation-rate-gauge
  (-> (io.prometheus.client.Gauge/build)
      (.name "sim_generation_rate")
      (.help "Current request generation rate (RPS)")
      (.register (metrics/get-registry))))

(defn- record-request-generated!
  "Records a generated request in metrics."
  [request]
  (-> requests-generated-counter
      (.labels (into-array String [(name (:priority request))
                                   (:model-id request)]))
      (.inc)))

;; =============================================================================
;; Generator Loop
;; =============================================================================

(defn- generator-loop
  "Main generator loop that produces requests at configured RPS."
  [^Random rng ^TokenBucket bucket target-rps on-request stop-ch]
  (go-loop [batch-count 0]
    (let [[_ ch] (alts! [stop-ch (timeout 1)])]  ; Fast loop
      (if (= ch stop-ch)
        (log/info "Load generator stopped" {:batches batch-count
                                             :total-requests @requests-generated})
        (do
          ;; Try to generate a batch
          (when (acquire-tokens! bucket batch-size)
            (try
              (let [batch (generate-request-batch rng batch-size)]
                (doseq [req batch]
                  (on-request req)
                  (record-request-generated! req)
                  (swap! requests-generated inc)))
              (catch Exception e
                (log/error e "Error generating request batch"))))

          ;; Update metrics periodically
          (when (zero? (mod batch-count 100))
            (.set generation-rate-gauge (double target-rps)))

          (recur (inc batch-count)))))))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn start!
  "Starts the load generator.

   Options:
   - :rps - Requests per second (default 1000)
   - :seed - Random seed for reproducibility
   - :on-request - Callback function for each generated request (required)
   - :ramp-up-seconds - Gradual ramp-up time (default 0)"
  [{:keys [rps seed on-request ramp-up-seconds]
    :or {rps default-rps
         seed (System/currentTimeMillis)
         ramp-up-seconds 0}}]
  {:pre [(fn? on-request)]}
  (when @generator-running
    (throw (ex-info "Load generator already running" {:type :already-running})))

  (log/info "Starting load generator" {:rps rps
                                        :seed seed
                                        :ramp-up-seconds ramp-up-seconds})

  (let [rng (Random. seed)
        stop-ch (chan)]
    (reset! requests-generated 0)
    (reset! current-rps rps)
    (reset! generator-channel stop-ch)
    (reset! generator-running true)

    ;; Handle ramp-up
    (if (pos? ramp-up-seconds)
      ;; Gradual ramp-up
      (let [executor (Executors/newSingleThreadScheduledExecutor)]
        (reset! generator-executor executor)
        (let [step-count 10
              step-rps (/ rps step-count)
              step-delay-ms (/ (* ramp-up-seconds 1000) step-count)]
          (dotimes [i step-count]
            (let [target-rps (* step-rps (inc i))]
              (.schedule executor
                         (fn []
                           (log/info "Ramping up" {:target-rps target-rps})
                           (reset! current-rps target-rps))
                         (* i step-delay-ms)
                         TimeUnit/MILLISECONDS)))
          ;; Start with initial rate
          (let [bucket (create-token-bucket step-rps)]
            (generator-loop rng bucket step-rps on-request stop-ch))))
      ;; Immediate full rate
      (let [bucket (create-token-bucket rps)]
        (generator-loop rng bucket rps on-request stop-ch)))

    (log/info "Load generator started")
    stop-ch))

(defn stop!
  "Stops the load generator."
  []
  (log/info "Stopping load generator")
  (when-let [ch @generator-channel]
    (close! ch)
    (reset! generator-channel nil))
  (when-let [executor @generator-executor]
    (.shutdown ^ScheduledExecutorService executor)
    (reset! generator-executor nil))
  (reset! generator-running false)
  (log/info "Load generator stopped"))

(defn set-rps!
  "Dynamically adjusts the target RPS."
  [new-rps]
  {:pre [(pos? new-rps)]}
  (log/info "Adjusting RPS" {:old @current-rps :new new-rps})
  (reset! current-rps new-rps))

;; =============================================================================
;; Status
;; =============================================================================

(defn status
  "Returns generator status."
  []
  {:running @generator-running
   :target-rps @current-rps
   :total-generated @requests-generated})

;; =============================================================================
;; One-shot Generation (for testing)
;; =============================================================================

(defn generate-requests
  "Generates n requests without running the continuous generator.

   Returns a vector of requests."
  [n & {:keys [seed] :or {seed (System/currentTimeMillis)}}]
  (let [rng (Random. seed)]
    (vec (repeatedly n #(generate-request rng)))))

(defn request-distribution
  "Analyzes the distribution of generated requests.

   Returns statistics about priority and model distribution."
  [requests]
  (let [priorities (frequencies (map :priority requests))
        models (frequencies (map :model-id requests))
        contexts (map :context-window-tokens requests)]
    {:count (count requests)
     :priority-distribution priorities
     :priority-percentages (into {} (map (fn [[k v]] [k (* 100.0 (/ v (count requests)))])
                                         priorities))
     :model-distribution models
     :model-percentages (into {} (map (fn [[k v]] [k (* 100.0 (/ v (count requests)))])
                                      models))
     :context-tokens {:min (apply min contexts)
                      :max (apply max contexts)
                      :avg (/ (reduce + contexts) (count requests))
                      :median (nth (sort contexts) (/ (count requests) 2))}}))
