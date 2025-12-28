(ns arcnet.config
  "Configuration management for ARCNet using Aero.

   Key principles:
   - ALL secrets come from environment variables (never hardcoded)
   - Environment-specific profiles (dev/staging/prod)
   - Spec validation of all configuration
   - Fail-fast on missing or invalid configuration

   Environment variables:
   - ARCNET_ENV: Environment profile (dev/staging/prod)
   - ARCNET_KAFKA_BOOTSTRAP_SERVERS: Kafka broker addresses
   - ARCNET_KAFKA_USERNAME: SASL username
   - ARCNET_KAFKA_PASSWORD: SASL password
   - ARCNET_KEYSTORE_PATH: Path to PKCS12 keystore
   - ARCNET_KEYSTORE_PASSWORD: Password for keystores
   - ARCNET_TRUSTSTORE_PATH: Path to PKCS12 truststore
   - ARCNET_TRUSTSTORE_PASSWORD: Password for truststores
   - ARCNET_NODE_ID: This node's identifier
   - ARCNET_GEOZONE_ID: This node's geozone"
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Specs - Environment Variables
;; =============================================================================

(s/def ::non-blank-string (s/and string? (complement str/blank?)))

;; Kafka configuration
(s/def ::bootstrap-servers ::non-blank-string)
(s/def ::kafka-username ::non-blank-string)
(s/def ::kafka-password (s/and string? #(>= (count %) 8)))
(s/def ::client-id ::non-blank-string)
(s/def ::group-id ::non-blank-string)
(s/def ::acks #{"all" "1" "0"})
(s/def ::retries nat-int?)

(s/def ::kafka-config
  (s/keys :req-un [::bootstrap-servers]
          :opt-un [::client-id ::group-id ::acks ::retries]))

;; TLS configuration
(s/def ::keystore-path ::non-blank-string)
(s/def ::keystore-password ::non-blank-string)
(s/def ::truststore-path ::non-blank-string)
(s/def ::truststore-password ::non-blank-string)

(s/def ::tls-config
  (s/keys :req-un [::keystore-path ::keystore-password
                   ::truststore-path ::truststore-password]))

;; SASL configuration
(s/def ::username ::non-blank-string)
(s/def ::password (s/and string? #(>= (count %) 8)))

(s/def ::sasl-config
  (s/keys :req-un [::username ::password]))

;; Node identity
(s/def ::node-id (s/and string? #(re-matches #"[a-z0-9\-]+" %)))
(s/def ::geozone-id (s/and string? #(re-matches #"[a-z]+-[a-z]+" %)))

(s/def ::node-identity
  (s/keys :req-un [::node-id ::geozone-id]))

;; Certificate configuration
(s/def ::root-ca-path ::non-blank-string)
(s/def ::geozone-ca-path ::non-blank-string)
(s/def ::node-cert-path ::non-blank-string)
(s/def ::node-key-path ::non-blank-string)
(s/def ::validity-days pos-int?)

(s/def ::cert-config
  (s/keys :opt-un [::root-ca-path ::geozone-ca-path
                   ::node-cert-path ::node-key-path
                   ::validity-days]))

;; Environment
(s/def ::env #{:dev :staging :prod})

;; Complete configuration
(s/def ::config
  (s/keys :req-un [::env ::node-identity ::kafka-config
                   ::tls-config ::sasl-config]
          :opt-un [::cert-config]))

;; =============================================================================
;; Aero Custom Readers
;; =============================================================================

(defmethod aero/reader 'env-required
  [_ _ value]
  "Reads a required environment variable. Throws if not set."
  (let [env-var (name value)
        result (System/getenv env-var)]
    (when (str/blank? result)
      (throw (ex-info (str "Required environment variable not set: " env-var)
                      {:env-var env-var
                       :type :missing-env-var})))
    result))

(defmethod aero/reader 'env-secret
  [_ _ value]
  "Reads a secret from environment variable.
   Same as env-required but semantically indicates sensitive data.
   SECURITY: This value should never be logged."
  (let [env-var (name value)
        result (System/getenv env-var)]
    (when (str/blank? result)
      (throw (ex-info (str "Required secret not set: " env-var)
                      {:env-var env-var
                       :type :missing-secret})))
    result))

(defmethod aero/reader 'env-int
  [_ _ [env-var default]]
  "Reads an integer from environment variable with default."
  (let [value (System/getenv (name env-var))]
    (if (str/blank? value)
      default
      (Integer/parseInt value))))

(defmethod aero/reader 'env-bool
  [_ _ [env-var default]]
  "Reads a boolean from environment variable with default."
  (let [value (System/getenv (name env-var))]
    (if (str/blank? value)
      default
      (Boolean/parseBoolean value))))

(defmethod aero/reader 'path
  [_ _ segments]
  "Constructs a file path from segments."
  (apply io/file segments))

;; =============================================================================
;; Configuration Loading
;; =============================================================================

(defn- get-environment
  "Determines the current environment from ARCNET_ENV.
   Defaults to :dev if not set."
  []
  (let [env-str (System/getenv "ARCNET_ENV")]
    (case env-str
      "prod" :prod
      "production" :prod
      "staging" :staging
      "stage" :staging
      :dev)))

(defn load-config
  "Loads ARCNet configuration from resources/config.edn.

   Uses Aero for configuration management with:
   - Environment-specific profiles
   - Environment variable interpolation
   - Spec validation

   Options:
   - :profile - Override environment detection
   - :validate? - Whether to validate config (default true)

   Returns validated configuration map."
  ([] (load-config {}))
  ([{:keys [profile validate? config-path]
     :or {validate? true
          config-path "config.edn"}}]
   (let [env (or profile (get-environment))
         _ (log/info "Loading configuration" {:env env})
         config-resource (io/resource config-path)]
     (when-not config-resource
       (throw (ex-info "Configuration file not found"
                       {:path config-path
                        :type :config-not-found})))
     (let [config (aero/read-config config-resource {:profile env})]
       (when validate?
         (when-not (s/valid? ::config config)
           (let [explanation (s/explain-str ::config config)]
             (log/error "Invalid configuration" {:explanation explanation})
             (throw (ex-info "Configuration validation failed"
                             {:type :config-validation-failed
                              :explanation explanation})))))
       (log/info "Configuration loaded successfully"
                 {:env env
                  :node-id (get-in config [:node-identity :node-id])
                  :geozone (get-in config [:node-identity :geozone-id])})
       config))))

;; =============================================================================
;; Configuration Access Helpers
;; =============================================================================

(defn kafka-producer-config
  "Extracts Kafka producer configuration from loaded config.
   Returns a map compatible with arcnet.security.kafka/secure-producer-config."
  [config]
  (let [{:keys [kafka-config tls-config sasl-config]} config]
    {:bootstrap-servers (:bootstrap-servers kafka-config)
     :client-id (:client-id kafka-config)
     :tls-config tls-config
     :sasl-config sasl-config
     :acks (get kafka-config :acks "all")
     :retries (get kafka-config :retries 3)}))

(defn kafka-consumer-config
  "Extracts Kafka consumer configuration from loaded config.
   Returns a map compatible with arcnet.security.kafka/secure-consumer-config."
  [config]
  (let [{:keys [kafka-config tls-config sasl-config]} config]
    {:bootstrap-servers (:bootstrap-servers kafka-config)
     :group-id (:group-id kafka-config)
     :client-id (:client-id kafka-config)
     :tls-config tls-config
     :sasl-config sasl-config}))

(defn node-identity
  "Extracts node identity from configuration."
  [config]
  (:node-identity config))

(defn environment
  "Returns the current environment (:dev, :staging, or :prod)."
  [config]
  (:env config))

(defn production?
  "Returns true if running in production environment."
  [config]
  (= :prod (:env config)))

(defn development?
  "Returns true if running in development environment."
  [config]
  (= :dev (:env config)))

;; =============================================================================
;; Configuration Validation Helpers
;; =============================================================================

(defn validate-paths-exist
  "Validates that all file paths in the configuration exist.
   Returns a map of {:valid? bool :missing [paths]}."
  [config]
  (let [paths [[:tls-config :keystore-path]
               [:tls-config :truststore-path]]
        missing (for [path paths
                      :let [file-path (get-in config path)]
                      :when (and file-path
                                 (not (.exists (io/file file-path))))]
                  {:path path :file file-path})]
    (if (seq missing)
      {:valid? false :missing missing}
      {:valid? true :missing []})))

(defn explain-config
  "Returns a human-readable explanation of configuration issues."
  [config]
  (let [spec-result (s/explain-data ::config config)
        path-result (validate-paths-exist config)]
    (cond
      spec-result
      {:valid? false
       :type :spec-failure
       :problems (:clojure.spec.alpha/problems spec-result)}

      (not (:valid? path-result))
      {:valid? false
       :type :missing-files
       :missing (:missing path-result)}

      :else
      {:valid? true})))

;; =============================================================================
;; Secret Masking for Logging
;; =============================================================================

(defn mask-secrets
  "Returns a copy of config with secrets masked for safe logging.
   NEVER log raw configuration - always use this function first."
  [config]
  (-> config
      (assoc-in [:sasl-config :password] "***MASKED***")
      (assoc-in [:tls-config :keystore-password] "***MASKED***")
      (assoc-in [:tls-config :truststore-password] "***MASKED***")))

(defn log-config
  "Safely logs configuration with secrets masked."
  [config]
  (log/info "Current configuration" (mask-secrets config)))
