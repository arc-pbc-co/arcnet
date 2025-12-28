(ns arcnet.bridge.classifier
  "Workload classification for ARCNet job routing.

   Determines whether a training job should be routed to:
   - :hpc (ORNL Frontier/Lux) for heavy workloads
   - :federated (distributed across ARCNet geozones) for lighter workloads

   Classification thresholds:
   - Dataset size > 1TB (1000 GB) → HPC
   - Estimated FLOPS > 1e18 (1 exaFLOP) → HPC

   This is a pure function module with no side effects."
  (:require [clojure.tools.logging :as log]))

;; =============================================================================
;; Classification Thresholds
;; =============================================================================

(def ^:const dataset-size-threshold-gb
  "Dataset size threshold for HPC routing (1 TB)."
  1000.0)

(def ^:const estimated-flops-threshold
  "FLOPS threshold for HPC routing (1 exaFLOP = 10^18)."
  1e18)

;; Additional thresholds for fine-grained classification
(def ^:const gpu-memory-threshold-gb
  "Minimum GPU memory requirement that suggests HPC (256 GB aggregate)."
  256.0)

(def ^:const checkpoint-size-threshold-gb
  "Checkpoint size that suggests HPC storage requirements (100 GB)."
  100.0)

;; =============================================================================
;; Classification Reasons
;; =============================================================================

(def classification-reasons
  "Human-readable reasons for classification decisions."
  {:dataset-size "Dataset exceeds 1TB threshold"
   :estimated-flops "Compute requirements exceed 1 exaFLOP"
   :gpu-memory "GPU memory requirements exceed federated capacity"
   :checkpoint-size "Checkpoint storage exceeds federated limits"
   :explicit-hpc "Job explicitly requested HPC target"
   :explicit-federated "Job explicitly requested federated target"
   :default-federated "Workload within federated network capacity"})

;; =============================================================================
;; Pure Classification Function
;; =============================================================================

(defn classify-workload
  "Classifies a training job for routing.

   Pure function - no side effects.

   Parameters:
   - job: A TrainingJob map with keys:
     - :dataset-size-gb (required)
     - :estimated-flops (required)
     - :checkpoint-uri (optional)
     - :target-override (optional) - :hpc or :federated to force routing

   Returns:
   {:target :hpc | :federated
    :reason \"Human readable reason\"
    :classification-factors {...}}"
  [job]
  {:pre [(map? job)
         (number? (:dataset-size-gb job))
         (number? (:estimated-flops job))]}
  (let [dataset-size-gb (double (:dataset-size-gb job))
        estimated-flops (double (:estimated-flops job))
        target-override (:target-override job)
        ;; Calculate classification factors
        exceeds-dataset-threshold? (> dataset-size-gb dataset-size-threshold-gb)
        exceeds-flops-threshold? (> estimated-flops estimated-flops-threshold)
        ;; Build factor map for observability
        factors {:dataset-size-gb dataset-size-gb
                 :estimated-flops estimated-flops
                 :exceeds-dataset-threshold exceeds-dataset-threshold?
                 :exceeds-flops-threshold exceeds-flops-threshold?
                 :target-override target-override}]
    (cond
      ;; Explicit override takes precedence
      (= :hpc target-override)
      {:target :hpc
       :reason (:explicit-hpc classification-reasons)
       :classification-factors factors}

      (= :federated target-override)
      {:target :federated
       :reason (:explicit-federated classification-reasons)
       :classification-factors factors}

      ;; Dataset size threshold
      exceeds-dataset-threshold?
      {:target :hpc
       :reason (:dataset-size classification-reasons)
       :classification-factors factors}

      ;; FLOPS threshold
      exceeds-flops-threshold?
      {:target :hpc
       :reason (:estimated-flops classification-reasons)
       :classification-factors factors}

      ;; Default to federated
      :else
      {:target :federated
       :reason (:default-federated classification-reasons)
       :classification-factors factors})))

;; =============================================================================
;; Extended Classification (with more factors)
;; =============================================================================

(defn classify-workload-extended
  "Extended classification with additional factors.

   Considers:
   - Dataset size
   - Estimated FLOPS
   - GPU memory requirements
   - Checkpoint storage needs
   - Network bandwidth requirements

   Parameters:
   - job: TrainingJob with optional extended fields:
     - :required-gpu-memory-gb
     - :estimated-checkpoint-size-gb
     - :requires-high-bandwidth?

   Returns same structure as classify-workload."
  [job]
  (let [base-classification (classify-workload job)
        ;; Extended factors
        gpu-memory-gb (or (:required-gpu-memory-gb job) 0.0)
        checkpoint-gb (or (:estimated-checkpoint-size-gb job) 0.0)
        requires-bandwidth? (:requires-high-bandwidth? job false)
        ;; Extended checks
        exceeds-gpu-memory? (> gpu-memory-gb gpu-memory-threshold-gb)
        exceeds-checkpoint? (> checkpoint-gb checkpoint-size-threshold-gb)
        ;; Update factors
        extended-factors (merge (:classification-factors base-classification)
                                {:required-gpu-memory-gb gpu-memory-gb
                                 :estimated-checkpoint-size-gb checkpoint-gb
                                 :requires-high-bandwidth requires-bandwidth?
                                 :exceeds-gpu-memory-threshold exceeds-gpu-memory?
                                 :exceeds-checkpoint-threshold exceeds-checkpoint?})]
    (cond
      ;; Base classification already HPC
      (= :hpc (:target base-classification))
      (assoc base-classification :classification-factors extended-factors)

      ;; GPU memory threshold
      exceeds-gpu-memory?
      {:target :hpc
       :reason (:gpu-memory classification-reasons)
       :classification-factors extended-factors}

      ;; Checkpoint size threshold
      exceeds-checkpoint?
      {:target :hpc
       :reason (:checkpoint-size classification-reasons)
       :classification-factors extended-factors}

      ;; High bandwidth requirement suggests HPC interconnect
      requires-bandwidth?
      {:target :hpc
       :reason "Job requires high-bandwidth interconnect"
       :classification-factors extended-factors}

      ;; Default
      :else
      (assoc base-classification :classification-factors extended-factors))))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn hpc-bound?
  "Returns true if the job should be routed to HPC."
  [job]
  (= :hpc (:target (classify-workload job))))

(defn federated-bound?
  "Returns true if the job should be routed to federated network."
  [job]
  (= :federated (:target (classify-workload job))))

(defn estimate-hpc-priority
  "Estimates HPC queue priority based on job characteristics.

   Returns :high, :normal, or :low.

   Higher priority for:
   - Larger datasets (more likely to benefit from HPC)
   - Higher FLOPS requirements"
  [job]
  (let [dataset-size-gb (double (:dataset-size-gb job))
        estimated-flops (double (:estimated-flops job))]
    (cond
      ;; Very large jobs get high priority
      (or (> dataset-size-gb (* 10 dataset-size-threshold-gb))
          (> estimated-flops (* 10 estimated-flops-threshold)))
      :high

      ;; Jobs well above threshold get normal priority
      (or (> dataset-size-gb (* 2 dataset-size-threshold-gb))
          (> estimated-flops (* 2 estimated-flops-threshold)))
      :normal

      ;; Jobs near threshold get low priority
      :else
      :low)))

(defn format-classification
  "Formats a classification result for logging/display."
  [classification]
  (let [{:keys [target reason classification-factors]} classification]
    (format "Target: %s | Reason: %s | Dataset: %.2f GB | FLOPS: %.2e"
            (name target)
            reason
            (:dataset-size-gb classification-factors)
            (:estimated-flops classification-factors))))

;; =============================================================================
;; Batch Classification
;; =============================================================================

(defn classify-batch
  "Classifies a batch of jobs, grouping by target.

   Returns:
   {:hpc [jobs...]
    :federated [jobs...]}"
  [jobs]
  (let [classified (map (fn [job]
                          (assoc job :classification (classify-workload job)))
                        jobs)]
    {:hpc (filter #(= :hpc (get-in % [:classification :target])) classified)
     :federated (filter #(= :federated (get-in % [:classification :target])) classified)}))

;; =============================================================================
;; Classification Statistics
;; =============================================================================

(defn classification-stats
  "Computes statistics for a batch of classifications.

   Useful for monitoring and capacity planning."
  [jobs]
  (let [classified (map classify-workload jobs)
        hpc-jobs (filter #(= :hpc (:target %)) classified)
        federated-jobs (filter #(= :federated (:target %)) classified)]
    {:total (count jobs)
     :hpc-count (count hpc-jobs)
     :federated-count (count federated-jobs)
     :hpc-percentage (if (pos? (count jobs))
                       (* 100.0 (/ (count hpc-jobs) (count jobs)))
                       0.0)
     :hpc-reasons (frequencies (map :reason hpc-jobs))
     :federated-reasons (frequencies (map :reason federated-jobs))}))
