(ns arcnet.schema.registry
  "Malli schema registry for ARCNet core entity types.

   All schemas include a mandatory :schema/version field for evolution.
   Version changes require explicit migration functions.

   Schema naming convention:
   - :arcnet/EntityName for current version
   - :arcnet/EntityName.v1, :arcnet/EntityName.v2 for versioned schemas"
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [malli.util :as mu]
            [malli.error :as me]
            [malli.transform :as mt]
            [clj-uuid :as uuid]))

;; =============================================================================
;; Current Schema Version
;; =============================================================================

(def ^:const current-schema-version 2)

;; =============================================================================
;; Base Schema Components
;; =============================================================================

(def base-schemas
  {;; Schema version - required on all entities
   :schema/version [:int {:min 1}]

   ;; Common identifiers
   :arcnet/uuid [:uuid]
   :arcnet/timestamp [:inst]

   ;; Geohash - 6 character precision (~1.2km)
   :arcnet/geohash [:string {:min 6 :max 6
                             :description "6-character geohash for location"}]

   ;; Energy sources available in the network
   :arcnet/energy-source [:enum :solar :grid :battery]

   ;; Priority levels for inference requests
   :arcnet/priority [:enum :critical :normal :background]

   ;; Normalized percentage (0.0 to 1.0)
   :arcnet/percentage [:double {:min 0.0 :max 1.0}]

   ;; Model identifier
   :arcnet/model-id [:string {:min 1 :max 128}]

   ;; URI for external resources
   :arcnet/uri [:string {:min 1}]})

;; =============================================================================
;; NodeTelemetry Schema (v2)
;; =============================================================================

(def node-telemetry-v2
  "Telemetry data from a compute node in the ARCNet mesh."
  [:map {:closed true
         :description "Node telemetry snapshot"}
   [:schema/version [:= 2]]
   [:id :arcnet/uuid]
   [:timestamp :arcnet/timestamp]
   [:geohash :arcnet/geohash]
   [:energy-source :arcnet/energy-source]
   [:battery-level :arcnet/percentage]
   [:gpu-utilization :arcnet/percentage]
   [:gpu-memory-free-gb [:double {:min 0}]]
   [:models-loaded [:vector :arcnet/model-id]]])

;; V1 schema for migration reference
(def node-telemetry-v1
  "Legacy v1 schema - energy-source was a string, not enum."
  [:map {:closed true}
   [:schema/version [:= 1]]
   [:id :arcnet/uuid]
   [:timestamp :arcnet/timestamp]
   [:geohash :arcnet/geohash]
   [:energy-source [:string]]  ; v1 used strings
   [:battery-level :arcnet/percentage]
   [:gpu-utilization :arcnet/percentage]
   [:gpu-memory-free-gb [:double {:min 0}]]
   [:models-loaded [:vector :arcnet/model-id]]])

;; =============================================================================
;; InferenceRequest Schema (v2)
;; =============================================================================

(def inference-request-v2
  "Request for model inference with routing hints."
  [:map {:closed true
         :description "Inference request with routing metadata"}
   [:schema/version [:= 2]]
   [:id :arcnet/uuid]
   [:model-id :arcnet/model-id]
   [:context-window-tokens [:int {:min 1}]]
   [:priority :arcnet/priority]
   [:max-latency-ms [:int {:min 1}]]
   [:requester-geozone [:string {:min 1 :max 32}]]])

;; V1 schema - priority was integer 1-3
(def inference-request-v1
  "Legacy v1 schema - priority was integer."
  [:map {:closed true}
   [:schema/version [:= 1]]
   [:id :arcnet/uuid]
   [:model-id :arcnet/model-id]
   [:context-window-tokens [:int {:min 1}]]
   [:priority [:int {:min 1 :max 3}]]  ; v1: 1=critical, 2=normal, 3=background
   [:max-latency-ms [:int {:min 1}]]
   [:requester-geozone [:string {:min 1 :max 32}]]])

;; =============================================================================
;; TrainingJob Schema (v2)
;; =============================================================================

(def training-job-v2
  "Distributed training job specification."
  [:map {:closed true
         :description "Training job with resource estimates"}
   [:schema/version [:= 2]]
   [:id :arcnet/uuid]
   [:dataset-uri :arcnet/uri]
   [:dataset-size-gb [:double {:min 0}]]
   [:estimated-flops [:double {:min 0}]]
   [:checkpoint-uri {:optional true} :arcnet/uri]])

;; V1 schema - dataset-size was integer GB
(def training-job-v1
  "Legacy v1 schema - dataset-size was integer."
  [:map {:closed true}
   [:schema/version [:= 1]]
   [:id :arcnet/uuid]
   [:dataset-uri :arcnet/uri]
   [:dataset-size-gb [:int {:min 0}]]  ; v1 was integer
   [:estimated-flops [:double {:min 0}]]
   [:checkpoint-uri {:optional true} :arcnet/uri]])

;; =============================================================================
;; Schema Registry
;; =============================================================================

(def entity-schemas
  "All entity schemas indexed by name and version."
  {;; Current versions (unversioned names point to latest)
   :arcnet/NodeTelemetry node-telemetry-v2
   :arcnet/InferenceRequest inference-request-v2
   :arcnet/TrainingJob training-job-v2

   ;; Versioned schemas for migration
   :arcnet/NodeTelemetry.v1 node-telemetry-v1
   :arcnet/NodeTelemetry.v2 node-telemetry-v2
   :arcnet/InferenceRequest.v1 inference-request-v1
   :arcnet/InferenceRequest.v2 inference-request-v2
   :arcnet/TrainingJob.v1 training-job-v1
   :arcnet/TrainingJob.v2 training-job-v2})

(def registry
  "Combined Malli registry with all ARCNet schemas."
  (mr/composite-registry
   (m/default-schemas)
   (mu/schemas)
   base-schemas
   entity-schemas))

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate
  "Validates data against a schema. Returns data if valid, nil if invalid."
  [schema data]
  (m/validate schema data {:registry registry}))

(defn explain
  "Returns explanation of validation errors, or nil if valid."
  [schema data]
  (m/explain schema data {:registry registry}))

(defn humanize-errors
  "Returns human-readable error messages."
  [schema data]
  (some-> (explain schema data)
          (me/humanize)))

(defn validate!
  "Validates data, throws ex-info on failure with humanized errors."
  [schema data]
  (if (validate schema data)
    data
    (throw (ex-info "Schema validation failed"
                    {:type :arcnet/validation-error
                     :schema (if (keyword? schema) schema (m/type schema))
                     :errors (humanize-errors schema data)
                     :data data}))))

(defn coerce
  "Coerces data to match schema using string transformer."
  [schema data]
  (m/coerce schema data (mt/string-transformer) {:registry registry}))

;; =============================================================================
;; Schema Introspection
;; =============================================================================

(defn get-schema
  "Retrieves a schema by keyword from the registry."
  [schema-key]
  (mr/schema registry schema-key))

(defn schema-version
  "Extracts the version number from a schema definition."
  [schema]
  (let [props (m/properties schema {:registry registry})
        children (m/children schema {:registry registry})
        version-child (first (filter #(= :schema/version (first %)) children))]
    (when version-child
      (let [version-schema (second version-child)]
        (when (and (vector? version-schema) (= := (first version-schema)))
          (second version-schema))))))

(defn entity-type
  "Extracts the entity type from data based on schema/version."
  [data]
  (when-let [version (:schema/version data)]
    (cond
      (contains? data :gpu-utilization) :arcnet/NodeTelemetry
      (contains? data :context-window-tokens) :arcnet/InferenceRequest
      (contains? data :dataset-uri) :arcnet/TrainingJob
      :else nil)))

(defn versioned-schema-key
  "Returns the versioned schema key for an entity type and version."
  [entity-type version]
  (keyword (namespace entity-type)
           (str (name entity-type) ".v" version)))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn make-node-telemetry
  "Creates a validated NodeTelemetry entity."
  [{:keys [geohash energy-source battery-level
           gpu-utilization gpu-memory-free-gb models-loaded]}]
  (validate! :arcnet/NodeTelemetry
             {:schema/version current-schema-version
              :id (uuid/v4)
              :timestamp (java.util.Date.)
              :geohash geohash
              :energy-source energy-source
              :battery-level battery-level
              :gpu-utilization gpu-utilization
              :gpu-memory-free-gb gpu-memory-free-gb
              :models-loaded (vec models-loaded)}))

(defn make-inference-request
  "Creates a validated InferenceRequest entity."
  [{:keys [model-id context-window-tokens priority
           max-latency-ms requester-geozone]}]
  (validate! :arcnet/InferenceRequest
             {:schema/version current-schema-version
              :id (uuid/v4)
              :model-id model-id
              :context-window-tokens context-window-tokens
              :priority priority
              :max-latency-ms max-latency-ms
              :requester-geozone requester-geozone}))

(defn make-training-job
  "Creates a validated TrainingJob entity."
  [{:keys [dataset-uri dataset-size-gb estimated-flops checkpoint-uri]}]
  (validate! :arcnet/TrainingJob
             (cond-> {:schema/version current-schema-version
                      :id (uuid/v4)
                      :dataset-uri dataset-uri
                      :dataset-size-gb dataset-size-gb
                      :estimated-flops estimated-flops}
               checkpoint-uri (assoc :checkpoint-uri checkpoint-uri))))

;; =============================================================================
;; Schema Listing
;; =============================================================================

(defn list-schemas
  "Returns all registered ARCNet schema keys."
  []
  (keys entity-schemas))

(defn list-current-schemas
  "Returns only current (non-versioned) schema keys."
  []
  (filter #(not (re-find #"\.v\d+$" (name %))) (keys entity-schemas)))
