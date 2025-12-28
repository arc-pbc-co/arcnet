(ns arcnet.schema.migration
  "Schema migration functions for ARCNet message format evolution.

   Migration strategy:
   - Each version change has an explicit migration function
   - Migrations are composable (v1->v3 = v1->v2 + v2->v3)
   - Migrations are registered in a migration registry
   - Consumers can auto-migrate messages to current version"
  (:require [arcnet.schema.registry :as schema]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Migration Registry
;; =============================================================================

(defonce ^:private migrations
  "Registry of migration functions.
   Key: [entity-type from-version to-version]
   Value: migration function (fn [data] -> migrated-data)"
  (atom {}))

(defn register-migration!
  "Registers a migration function for an entity type version change."
  [entity-type from-version to-version migrate-fn]
  (let [key [entity-type from-version to-version]]
    (swap! migrations assoc key migrate-fn)
    (log/info "Registered migration"
              {:entity entity-type
               :from from-version
               :to to-version})))

(defn get-migration
  "Retrieves a migration function."
  [entity-type from-version to-version]
  (get @migrations [entity-type from-version to-version]))

;; =============================================================================
;; NodeTelemetry Migrations: v1 -> v2
;; =============================================================================

(def energy-source-v1->v2
  "Maps v1 string energy sources to v2 enum keywords."
  {"solar" :solar
   "grid" :grid
   "battery" :battery
   ;; Handle case variations
   "SOLAR" :solar
   "GRID" :grid
   "BATTERY" :battery})

(defn migrate-node-telemetry-v1->v2
  "Migrates NodeTelemetry from v1 to v2.

   Changes:
   - :schema/version 1 -> 2
   - :energy-source string -> keyword enum

   Example:
   v1: {:energy-source \"solar\" ...}
   v2: {:energy-source :solar ...}"
  [data]
  (-> data
      (assoc :schema/version 2)
      (update :energy-source #(get energy-source-v1->v2 % :grid))))

(register-migration! :arcnet/NodeTelemetry 1 2 migrate-node-telemetry-v1->v2)

;; =============================================================================
;; InferenceRequest Migrations: v1 -> v2
;; =============================================================================

(def priority-v1->v2
  "Maps v1 integer priorities to v2 enum keywords."
  {1 :critical
   2 :normal
   3 :background})

(defn migrate-inference-request-v1->v2
  "Migrates InferenceRequest from v1 to v2.

   Changes:
   - :schema/version 1 -> 2
   - :priority integer (1-3) -> keyword enum

   Example:
   v1: {:priority 1 ...}
   v2: {:priority :critical ...}"
  [data]
  (-> data
      (assoc :schema/version 2)
      (update :priority #(get priority-v1->v2 % :normal))))

(register-migration! :arcnet/InferenceRequest 1 2 migrate-inference-request-v1->v2)

;; =============================================================================
;; TrainingJob Migrations: v1 -> v2
;; =============================================================================

(defn migrate-training-job-v1->v2
  "Migrates TrainingJob from v1 to v2.

   Changes:
   - :schema/version 1 -> 2
   - :dataset-size-gb integer -> double

   Example:
   v1: {:dataset-size-gb 100 ...}
   v2: {:dataset-size-gb 100.0 ...}"
  [data]
  (-> data
      (assoc :schema/version 2)
      (update :dataset-size-gb double)))

(register-migration! :arcnet/TrainingJob 1 2 migrate-training-job-v1->v2)

;; =============================================================================
;; Migration Execution
;; =============================================================================

(defn find-migration-path
  "Finds a sequence of migrations to get from one version to another.
   Returns a vector of [from to] pairs, or nil if no path exists."
  [entity-type from-version to-version]
  (cond
    (= from-version to-version) []
    (> from-version to-version) nil  ; Downgrade not supported
    :else
    ;; Simple linear path (v1->v2->v3...)
    (let [path (for [v (range from-version to-version)]
                 [v (inc v)])]
      (when (every? #(get-migration entity-type (first %) (second %)) path)
        path))))

(defn migrate
  "Migrates data from one version to another.

   Parameters:
   - entity-type: The entity schema key (e.g., :arcnet/NodeTelemetry)
   - data: The data to migrate
   - target-version: The version to migrate to

   Returns migrated data or throws if migration not possible."
  [entity-type data target-version]
  (let [current-version (:schema/version data)]
    (cond
      (nil? current-version)
      (throw (ex-info "Data missing :schema/version"
                      {:type :arcnet/migration-error
                       :entity entity-type
                       :data data}))

      (= current-version target-version)
      data

      :else
      (let [path (find-migration-path entity-type current-version target-version)]
        (if path
          (reduce
           (fn [d [from to]]
             (let [migrate-fn (get-migration entity-type from to)]
               (log/debug "Applying migration"
                          {:entity entity-type :from from :to to})
               (migrate-fn d)))
           data
           path)
          (throw (ex-info "No migration path found"
                          {:type :arcnet/migration-error
                           :entity entity-type
                           :from current-version
                           :to target-version})))))))

(defn migrate-to-current
  "Migrates data to the current schema version."
  [entity-type data]
  (migrate entity-type data schema/current-schema-version))

;; =============================================================================
;; Auto-Migration Consumer Middleware
;; =============================================================================

(defn auto-migrate-handler
  "Wraps a message handler with automatic migration to current version.

   The handler receives data at the current schema version."
  [entity-type handler]
  (fn [data metadata]
    (let [migrated (migrate-to-current entity-type data)]
      (handler migrated metadata))))

;; =============================================================================
;; Migration Testing Utilities
;; =============================================================================

(defn round-trip-migration
  "Tests that data can be migrated and still validates.

   Returns {:valid? bool :original data :migrated data :errors errors}"
  [entity-type data target-version]
  (try
    (let [migrated (migrate entity-type data target-version)
          schema-key (schema/versioned-schema-key entity-type target-version)
          valid? (schema/validate schema-key migrated)
          errors (when-not valid?
                   (schema/humanize-errors schema-key migrated))]
      {:valid? (boolean valid?)
       :original data
       :migrated migrated
       :errors errors})
    (catch Exception e
      {:valid? false
       :original data
       :error (.getMessage e)})))

(defn validate-migration-path
  "Validates that all migrations in a path produce valid output.

   Returns a report of each step."
  [entity-type sample-data from-version to-version]
  (let [path (find-migration-path entity-type from-version to-version)]
    (if-not path
      {:success? false :error "No migration path found"}
      (loop [data sample-data
             [step & remaining] path
             report []]
        (if-not step
          {:success? true :steps report :final-data data}
          (let [[from to] step
                migrate-fn (get-migration entity-type from to)
                migrated (migrate-fn data)
                schema-key (schema/versioned-schema-key entity-type to)
                valid? (schema/validate schema-key migrated)
                step-report {:from from
                             :to to
                             :valid? (boolean valid?)
                             :errors (when-not valid?
                                       (schema/humanize-errors schema-key migrated))}]
            (if valid?
              (recur migrated remaining (conj report step-report))
              {:success? false
               :steps (conj report step-report)
               :failed-at step})))))))

;; =============================================================================
;; Example Usage Documentation
;; =============================================================================

(comment
  ;; Example: Migrating a v1 NodeTelemetry to v2

  (def v1-telemetry
    {:schema/version 1
     :id #uuid "550e8400-e29b-41d4-a716-446655440000"
     :timestamp #inst "2024-01-15T10:30:00Z"
     :geohash "9q8yyk"
     :energy-source "solar"  ; v1: string
     :battery-level 0.85
     :gpu-utilization 0.72
     :gpu-memory-free-gb 16.5
     :models-loaded ["llama-70b" "whisper-large"]})

  ;; Migrate to v2
  (migrate :arcnet/NodeTelemetry v1-telemetry 2)
  ;; => {:schema/version 2
  ;;     ...
  ;;     :energy-source :solar  ; v2: keyword
  ;;     ...}

  ;; Validate the migration path
  (validate-migration-path :arcnet/NodeTelemetry v1-telemetry 1 2)
  ;; => {:success? true
  ;;     :steps [{:from 1 :to 2 :valid? true :errors nil}]
  ;;     :final-data {...}}

  ;; Auto-migrate in consumer
  (def my-handler
    (auto-migrate-handler
     :arcnet/NodeTelemetry
     (fn [data metadata]
       ;; data is always at current version
       (println "Processing:" (:energy-source data))))))
