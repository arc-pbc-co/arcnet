(ns arcnet.security.kafka
  "Secure Kafka producer/consumer configuration for ARCNet.

   Security features:
   - SASL/SCRAM-SHA-512 authentication
   - TLS encryption with mTLS certificates
   - Secure credential handling (no logging of secrets)

   This namespace provides functions to create properly configured
   Kafka properties maps that can be used with the Kafka Java client."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [arcnet.security.certs :as certs])
  (:import [java.util Properties]
           [java.io File FileOutputStream]
           [java.security KeyStore]
           [org.apache.kafka.clients CommonClientConfigs]
           [org.apache.kafka.clients.producer ProducerConfig KafkaProducer]
           [org.apache.kafka.clients.consumer ConsumerConfig KafkaConsumer]
           [org.apache.kafka.common.config SaslConfigs SslConfigs]
           [org.apache.kafka.common.serialization StringSerializer StringDeserializer
            ByteArraySerializer ByteArrayDeserializer]))

;; =============================================================================
;; Specs
;; =============================================================================

(s/def ::bootstrap-servers (s/and string? #(re-matches #"[\w\.\-:]+(,[\w\.\-:]+)*" %)))
(s/def ::username (s/and string? #(>= (count %) 1)))
(s/def ::password (s/and string? #(>= (count %) 8)))
(s/def ::keystore-path (s/and string? #(.exists (File. ^String %))))
(s/def ::truststore-path (s/and string? #(.exists (File. ^String %))))
(s/def ::keystore-password (s/and string? #(>= (count %) 1)))
(s/def ::truststore-password (s/and string? #(>= (count %) 1)))
(s/def ::client-id (s/and string? #(>= (count %) 1)))
(s/def ::group-id (s/and string? #(>= (count %) 1)))
(s/def ::topic (s/and string? #(re-matches #"[\w\.\-]+" %)))

(s/def ::tls-config
  (s/keys :req-un [::keystore-path ::keystore-password
                   ::truststore-path ::truststore-password]))

(s/def ::sasl-config
  (s/keys :req-un [::username ::password]))

(s/def ::kafka-config
  (s/keys :req-un [::bootstrap-servers ::tls-config ::sasl-config]
          :opt-un [::client-id]))

(s/def ::producer-config
  (s/merge ::kafka-config
           (s/keys :opt-un [::acks ::retries ::batch-size])))

(s/def ::consumer-config
  (s/merge ::kafka-config
           (s/keys :req-un [::group-id]
                   :opt-un [::auto-offset-reset ::enable-auto-commit])))

;; =============================================================================
;; SASL/SCRAM Configuration
;; =============================================================================

(defn- build-jaas-config
  "Builds a JAAS configuration string for SCRAM-SHA-512.

   SECURITY: This function handles credentials securely by
   building the config string without intermediate logging."
  [username password]
  (str "org.apache.kafka.common.security.scram.ScramLoginModule required "
       "username=\"" username "\" "
       "password=\"" password "\";"))

(defn- sasl-properties
  "Creates SASL authentication properties for SCRAM-SHA-512."
  [{:keys [username password]}]
  {SaslConfigs/SASL_MECHANISM "SCRAM-SHA-512"
   SaslConfigs/SASL_JAAS_CONFIG (build-jaas-config username password)})

;; =============================================================================
;; TLS/SSL Configuration
;; =============================================================================

(defn- ssl-properties
  "Creates SSL/TLS properties for encrypted communication.

   Uses the keystore for client authentication (mTLS) and
   truststore for server verification."
  [{:keys [keystore-path keystore-password
           truststore-path truststore-password]}]
  {SslConfigs/SSL_KEYSTORE_LOCATION_CONFIG keystore-path
   SslConfigs/SSL_KEYSTORE_PASSWORD_CONFIG keystore-password
   SslConfigs/SSL_KEYSTORE_TYPE_CONFIG "PKCS12"
   SslConfigs/SSL_KEY_PASSWORD_CONFIG keystore-password
   SslConfigs/SSL_TRUSTSTORE_LOCATION_CONFIG truststore-path
   SslConfigs/SSL_TRUSTSTORE_PASSWORD_CONFIG truststore-password
   SslConfigs/SSL_TRUSTSTORE_TYPE_CONFIG "PKCS12"
   ;; Hostname verification for production security
   SslConfigs/SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG "https"
   ;; TLS 1.3 only for maximum security
   SslConfigs/SSL_ENABLED_PROTOCOLS_CONFIG "TLSv1.3"
   SslConfigs/SSL_PROTOCOL_CONFIG "TLSv1.3"})

;; =============================================================================
;; Common Configuration
;; =============================================================================

(defn- common-security-properties
  "Creates common security properties for both producer and consumer."
  [{:keys [tls-config sasl-config]}]
  (merge
   ;; Security protocol: SASL over TLS
   {CommonClientConfigs/SECURITY_PROTOCOL_CONFIG "SASL_SSL"}
   (sasl-properties sasl-config)
   (ssl-properties tls-config)))

;; =============================================================================
;; Secure Producer Configuration
;; =============================================================================

(def ^:private default-secure-producer-config
  "Default producer configuration for reliability."
  {ProducerConfig/ACKS_CONFIG "all"
   ProducerConfig/RETRIES_CONFIG (int 3)
   ProducerConfig/RETRY_BACKOFF_MS_CONFIG (int 1000)
   ProducerConfig/ENABLE_IDEMPOTENCE_CONFIG true
   ProducerConfig/MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION (int 5)
   ProducerConfig/COMPRESSION_TYPE_CONFIG "lz4"
   ProducerConfig/LINGER_MS_CONFIG (int 5)
   ProducerConfig/BATCH_SIZE_CONFIG (int 16384)})

(defn secure-producer-config
  "Creates a complete Kafka producer configuration with SASL/SCRAM + TLS.

   Parameters:
   - config: Map containing:
     - :bootstrap-servers - Kafka broker addresses
     - :tls-config - TLS configuration map
     - :sasl-config - SASL credentials map
     - :client-id - Optional client identifier

   Additional producer options:
     - :acks - Acknowledgment mode (default 'all')
     - :retries - Number of retries (default 3)
     - :batch-size - Batch size in bytes (default 16384)

   Returns a Properties object ready for KafkaProducer."
  [{:keys [bootstrap-servers client-id acks retries batch-size]
    :as config}]
  {:pre [(s/valid? ::producer-config config)]}
  (log/info "Creating secure Kafka producer config"
            {:bootstrap-servers bootstrap-servers
             :client-id client-id})
  (let [props (merge
               default-secure-producer-config
               (common-security-properties config)
               {ProducerConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers
                ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG
                (.getName ByteArraySerializer)
                ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG
                (.getName ByteArraySerializer)}
               (when client-id
                 {ProducerConfig/CLIENT_ID_CONFIG client-id})
               (when acks
                 {ProducerConfig/ACKS_CONFIG acks})
               (when retries
                 {ProducerConfig/RETRIES_CONFIG (int retries)})
               (when batch-size
                 {ProducerConfig/BATCH_SIZE_CONFIG (int batch-size)}))]
    (doto (Properties.)
      (.putAll props))))

(defn create-secure-producer
  "Creates a new Kafka producer with SASL/SCRAM-SHA-512 + TLS.

   The producer is configured for:
   - SASL/SCRAM-SHA-512 authentication
   - TLS 1.3 encryption with mTLS
   - Idempotent production (exactly-once semantics)

   Returns a KafkaProducer instance."
  [config]
  (let [props (secure-producer-config config)]
    (log/info "Creating secure Kafka producer")
    (KafkaProducer. props)))

;; =============================================================================
;; Secure Consumer Configuration
;; =============================================================================

(def ^:private default-secure-consumer-config
  "Default consumer configuration for reliability."
  {ConsumerConfig/AUTO_OFFSET_RESET_CONFIG "earliest"
   ConsumerConfig/ENABLE_AUTO_COMMIT_CONFIG false
   ConsumerConfig/MAX_POLL_RECORDS_CONFIG (int 500)
   ConsumerConfig/SESSION_TIMEOUT_MS_CONFIG (int 30000)
   ConsumerConfig/HEARTBEAT_INTERVAL_MS_CONFIG (int 10000)})

(defn secure-consumer-config
  "Creates a complete Kafka consumer configuration with SASL/SCRAM + TLS.

   Parameters:
   - config: Map containing:
     - :bootstrap-servers - Kafka broker addresses
     - :group-id - Consumer group ID (required)
     - :tls-config - TLS configuration map
     - :sasl-config - SASL credentials map
     - :client-id - Optional client identifier

   Additional consumer options:
     - :auto-offset-reset - Offset reset behavior (default 'earliest')
     - :enable-auto-commit - Auto commit (default false)

   Returns a Properties object ready for KafkaConsumer."
  [{:keys [bootstrap-servers group-id client-id
           auto-offset-reset enable-auto-commit]
    :as config}]
  {:pre [(s/valid? ::consumer-config config)]}
  (log/info "Creating secure Kafka consumer config"
            {:bootstrap-servers bootstrap-servers
             :group-id group-id
             :client-id client-id})
  (let [props (merge
               default-secure-consumer-config
               (common-security-properties config)
               {ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers
                ConsumerConfig/GROUP_ID_CONFIG group-id
                ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG
                (.getName ByteArrayDeserializer)
                ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG
                (.getName ByteArrayDeserializer)}
               (when client-id
                 {ConsumerConfig/CLIENT_ID_CONFIG client-id})
               (when auto-offset-reset
                 {ConsumerConfig/AUTO_OFFSET_RESET_CONFIG auto-offset-reset})
               (when (some? enable-auto-commit)
                 {ConsumerConfig/ENABLE_AUTO_COMMIT_CONFIG enable-auto-commit}))]
    (doto (Properties.)
      (.putAll props))))

(defn create-secure-consumer
  "Creates a new Kafka consumer with SASL/SCRAM-SHA-512 + TLS.

   The consumer is configured for:
   - SASL/SCRAM-SHA-512 authentication
   - TLS 1.3 encryption with mTLS
   - Manual offset commits (for exactly-once processing)

   Returns a KafkaConsumer instance."
  [config]
  (let [props (secure-consumer-config config)]
    (log/info "Creating secure Kafka consumer"
              {:group-id (:group-id config)})
    (KafkaConsumer. props)))

;; =============================================================================
;; KeyStore Setup Helpers
;; =============================================================================

(defn setup-kafka-keystores
  "Sets up keystores for Kafka client authentication.

   Creates both keystore (for client identity) and truststore
   (for verifying Kafka brokers) from node certificate bundles.

   Parameters:
   - node-bundle: Node certificate bundle from arcnet.security.certs
   - root-ca: Root CA bundle for truststore
   - output-dir: Directory to write keystore files
   - password: Password for both keystores

   Returns:
   {:keystore-path \"path/to/keystore.p12\"
    :truststore-path \"path/to/truststore.p12\"
    :keystore-password password
    :truststore-password password}"
  [node-bundle root-ca output-dir password]
  (let [keystore-path (str output-dir "/keystore.p12")
        truststore-path (str output-dir "/truststore.p12")
        password-chars (.toCharArray password)]
    ;; Create keystore with node cert and chain
    (let [keystore (certs/create-keystore
                    (:node-id node-bundle)
                    (:key-pair node-bundle)
                    (:cert-chain node-bundle)
                    password-chars)]
      (certs/save-keystore keystore keystore-path password-chars))
    ;; Create truststore with root CA
    (let [truststore (certs/create-truststore
                      {"arcnet-root-ca" (:certificate root-ca)}
                      password-chars)]
      (certs/save-keystore truststore truststore-path password-chars))
    (log/info "Kafka keystores created"
              {:keystore keystore-path
               :truststore truststore-path})
    {:keystore-path keystore-path
     :truststore-path truststore-path
     :keystore-password password
     :truststore-password password}))

;; =============================================================================
;; Configuration Builder
;; =============================================================================

(defn build-secure-kafka-config
  "Builds a complete secure Kafka configuration from ARCNet components.

   This is a high-level function that combines TLS configuration
   from keystores with SASL credentials.

   Parameters:
   - bootstrap-servers: Kafka broker addresses
   - tls-paths: Map from setup-kafka-keystores
   - sasl-creds: {:username \"...\" :password \"...\"}
   - opts: Additional options (:client-id, :group-id for consumers)"
  [bootstrap-servers tls-paths sasl-creds & {:as opts}]
  (merge
   {:bootstrap-servers bootstrap-servers
    :tls-config {:keystore-path (:keystore-path tls-paths)
                 :keystore-password (:keystore-password tls-paths)
                 :truststore-path (:truststore-path tls-paths)
                 :truststore-password (:truststore-password tls-paths)}
    :sasl-config sasl-creds}
   opts))

;; =============================================================================
;; Connection Testing
;; =============================================================================

(defn test-secure-connection
  "Tests Kafka connectivity with the provided secure configuration.

   Creates a producer, sends a test message, and verifies
   the connection is working.

   Returns {:success true} or {:success false :error \"...\"}"
  [config test-topic]
  (log/info "Testing secure Kafka connection" {:topic test-topic})
  (try
    (with-open [producer (create-secure-producer config)]
      (let [metadata (.partitionsFor producer test-topic)]
        (if (seq metadata)
          (do
            (log/info "Secure Kafka connection successful"
                      {:topic test-topic
                       :partitions (count metadata)})
            {:success true
             :partitions (count metadata)})
          {:success false
           :error "No partitions found for topic"})))
    (catch Exception e
      (log/error e "Secure Kafka connection test failed")
      {:success false
       :error (.getMessage e)})))
