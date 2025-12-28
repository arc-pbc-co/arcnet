(ns arcnet.transport.kafka
  "Kafka producer/consumer wrapper for ARCNet with schema validation.

   Features:
   - Transit+msgpack serialization
   - Schema version in Kafka headers for consumer routing
   - Malli validation on produce (fail-fast)
   - Malli validation on consume (dead-letter on failure)
   - Thin interop over kafka-clients (no Jackdaw)
   - Integrated Prometheus metrics and OpenTelemetry tracing"
  (:require [arcnet.schema.registry :as schema]
            [arcnet.transport.serialization :as ser]
            [arcnet.observability.metrics :as metrics]
            [arcnet.observability.tracing :as tracing]
            [clojure.tools.logging :as log])
  (:import [java.time Duration]
           [java.util Properties]
           [org.apache.kafka.clients.consumer ConsumerConfig ConsumerRecord
            ConsumerRecords KafkaConsumer]
           [org.apache.kafka.clients.producer Callback KafkaProducer
            ProducerConfig ProducerRecord RecordMetadata]
           [org.apache.kafka.common.header Header Headers]
           [org.apache.kafka.common.header.internals RecordHeader RecordHeaders]
           [org.apache.kafka.common.serialization ByteArrayDeserializer
            ByteArraySerializer]))

;; =============================================================================
;; Header Constants
;; =============================================================================

(def ^:const header-schema-version "arcnet-schema-version")
(def ^:const header-entity-type "arcnet-entity-type")
(def ^:const header-original-topic "arcnet-original-topic")
(def ^:const header-error-message "arcnet-error")

;; =============================================================================
;; Dead Letter Topic Naming
;; =============================================================================

(defn dead-letter-topic
  "Returns the dead-letter topic name for a given topic."
  [topic]
  (str "arc.dead-letter." topic))

;; =============================================================================
;; Header Helpers
;; =============================================================================

(defn make-headers
  "Creates Kafka headers from a map.
   Keys can be keywords or strings, values are converted appropriately."
  ^Headers [header-map]
  (let [headers (RecordHeaders.)]
    (doseq [[k v] header-map]
      (.add headers (RecordHeader. (name k)
                                   (if (integer? v)
                                     (ser/int->bytes v)
                                     (ser/string->bytes (str v))))))
    headers))

(defn- parse-headers
  "Parses Kafka headers into a map."
  [^Headers headers]
  (when headers
    (into {}
          (for [^Header h (iterator-seq (.iterator headers))]
            [(keyword (.key h))
             (let [v (.value h)]
               (if (= header-schema-version (.key h))
                 (ser/bytes->int v)
                 (ser/bytes->string v)))]))))

;; =============================================================================
;; Producer Configuration
;; =============================================================================

(defn producer-config
  "Creates producer configuration properties.

   Options:
   - :bootstrap-servers - Required Kafka broker addresses
   - :client-id - Optional client identifier
   - :acks - Acknowledgment mode (default 'all')
   - :retries - Number of retries (default 3)
   - :additional - Map of additional Kafka properties"
  [{:keys [bootstrap-servers client-id acks retries additional]
    :or {acks "all" retries 3}}]
  (let [props (Properties.)]
    (.put props ProducerConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers)
    (.put props ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG
          (.getName ByteArraySerializer))
    (.put props ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG
          (.getName ByteArraySerializer))
    (.put props ProducerConfig/ACKS_CONFIG acks)
    (.put props ProducerConfig/RETRIES_CONFIG (int retries))
    (.put props ProducerConfig/ENABLE_IDEMPOTENCE_CONFIG true)
    (when client-id
      (.put props ProducerConfig/CLIENT_ID_CONFIG client-id))
    (doseq [[k v] additional]
      (.put props (name k) v))
    props))

;; =============================================================================
;; Consumer Configuration
;; =============================================================================

(defn consumer-config
  "Creates consumer configuration properties.

   Options:
   - :bootstrap-servers - Required Kafka broker addresses
   - :group-id - Required consumer group ID
   - :client-id - Optional client identifier
   - :auto-offset-reset - Offset reset behavior (default 'earliest')
   - :enable-auto-commit - Auto commit (default false for manual control)
   - :additional - Map of additional Kafka properties"
  [{:keys [bootstrap-servers group-id client-id
           auto-offset-reset enable-auto-commit additional]
    :or {auto-offset-reset "earliest"
         enable-auto-commit false}}]
  (let [props (Properties.)]
    (.put props ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers)
    (.put props ConsumerConfig/GROUP_ID_CONFIG group-id)
    (.put props ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG
          (.getName ByteArrayDeserializer))
    (.put props ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG
          (.getName ByteArrayDeserializer))
    (.put props ConsumerConfig/AUTO_OFFSET_RESET_CONFIG auto-offset-reset)
    (.put props ConsumerConfig/ENABLE_AUTO_COMMIT_CONFIG enable-auto-commit)
    (when client-id
      (.put props ConsumerConfig/CLIENT_ID_CONFIG client-id))
    (doseq [[k v] additional]
      (.put props (name k) v))
    props))

;; =============================================================================
;; Producer
;; =============================================================================

(defprotocol IArcNetProducer
  "Protocol for ARCNet Kafka producers."
  (send! [this topic schema-key data] [this topic schema-key data callback]
    "Sends a validated message to a topic.")
  (send-raw! [this topic key value headers]
    "Sends a raw message without validation (for dead-letter).")
  (close! [this]
    "Closes the producer."))

(defrecord ArcNetProducer [^KafkaProducer kafka-producer]
  IArcNetProducer

  (send! [this topic schema-key data]
    (send! this topic schema-key data nil))

  (send! [this topic schema-key data callback]
    (tracing/with-producer-span {:topic topic :schema (name schema-key)}
      (let [timer (metrics/start-timer)]
        (try
          ;; Validate BEFORE serialization
          (let [valid-data (schema/validate! schema-key data)
                version (:schema/version valid-data)
                entity-type (name schema-key)
                ;; Inject trace context into headers
                current-span (tracing/current-span)
                trace-headers (tracing/inject-trace-headers
                               {header-schema-version version
                                header-entity-type entity-type}
                               current-span)
                headers (make-headers trace-headers)
                key-bytes (some-> (:id valid-data) str ser/string->bytes)
                value-bytes (ser/serialize valid-data)
                record (ProducerRecord. ^String topic
                                        nil  ; partition
                                        key-bytes
                                        value-bytes
                                        headers)]
            (log/debug "Sending message" {:topic topic
                                          :schema schema-key
                                          :version version
                                          :id (:id valid-data)
                                          :trace-id (tracing/get-trace-id)})
            (let [result (if callback
                           (.send kafka-producer record
                                  (reify Callback
                                    (onCompletion [_ metadata exception]
                                      (callback metadata exception))))
                           (.send kafka-producer record))]
              ;; Record success metrics
              (metrics/record-kafka-produce!
               {:topic topic
                :schema entity-type
                :duration-ms (timer)
                :success? true})
              result))
          (catch clojure.lang.ExceptionInfo e
            ;; Validation failure
            (metrics/record-kafka-produce!
             {:topic topic
              :schema (name schema-key)
              :duration-ms (timer)
              :success? false})
            (metrics/record-validation-failure!
             {:schema (name schema-key)
              :direction :produce})
            (throw e))
          (catch Exception e
            ;; Other failures
            (metrics/record-kafka-produce!
             {:topic topic
              :schema (name schema-key)
              :duration-ms (timer)
              :success? false})
            (throw e))))))

  (send-raw! [_ topic key value headers]
    (let [record (ProducerRecord. ^String topic
                                  nil
                                  ^bytes key
                                  ^bytes value
                                  ^Headers headers)]
      (.send kafka-producer record)))

  (close! [_]
    (.close kafka-producer)))

(defn create-producer
  "Creates an ARCNet producer with validation.

   Config options:
   - :bootstrap-servers - Required
   - :client-id - Optional
   - :acks, :retries - Optional producer settings"
  [config]
  (let [props (producer-config config)
        producer (KafkaProducer. props)]
    (log/info "Created ARCNet producer"
              {:bootstrap-servers (:bootstrap-servers config)
               :client-id (:client-id config)})
    (->ArcNetProducer producer)))

;; =============================================================================
;; Consumer
;; =============================================================================

(defprotocol IArcNetConsumer
  "Protocol for ARCNet Kafka consumers."
  (subscribe! [this topics]
    "Subscribes to topics.")
  (poll! [this timeout-ms]
    "Polls for messages, returns validated records.")
  (commit! [this]
    "Commits current offsets.")
  (close! [this]
    "Closes the consumer."))

(defn- determine-schema
  "Determines the schema to validate against from headers or data."
  [headers data]
  (let [entity-type-str (get headers :arcnet-entity-type)
        version (or (get headers :arcnet-schema-version)
                    (:schema/version data))]
    (when (and entity-type-str version)
      (let [base-key (keyword entity-type-str)]
        (schema/versioned-schema-key base-key version)))))

(defn- process-record
  "Processes a single consumer record with validation.

   Returns {:status :valid :data data :metadata {...}}
   or {:status :invalid :error error :raw-data bytes :metadata {...}}"
  [^ConsumerRecord record]
  (let [topic (.topic record)
        partition (.partition record)
        offset (.offset record)
        headers (parse-headers (.headers record))
        metadata {:topic topic
                  :partition partition
                  :offset offset
                  :headers headers
                  :timestamp (.timestamp record)}
        timer (metrics/start-timer)
        schema-name (get headers :arcnet-entity-type "unknown")]
    ;; Use trace context from headers if available
    (tracing/with-consumer-span {:topic topic :schema schema-name :headers headers}
      (try
        (let [data (ser/deserialize (.value record))
              schema-key (determine-schema headers data)]
          (if schema-key
            ;; Validate against determined schema
            (if (schema/validate schema-key data)
              (do
                (metrics/record-kafka-consume!
                 {:topic topic
                  :schema (name schema-key)
                  :duration-ms (timer)
                  :success? true})
                {:status :valid
                 :data data
                 :schema schema-key
                 :metadata metadata})
              (do
                (metrics/record-kafka-consume!
                 {:topic topic
                  :schema (name schema-key)
                  :duration-ms (timer)
                  :success? false})
                (metrics/record-validation-failure!
                 {:schema (name schema-key)
                  :direction :consume})
                {:status :invalid
                 :error (schema/humanize-errors schema-key data)
                 :raw-data (.value record)
                 :attempted-data data
                 :schema schema-key
                 :metadata metadata}))
            ;; No schema determined - treat as raw
            (do
              (metrics/record-kafka-consume!
               {:topic topic
                :schema "raw"
                :duration-ms (timer)
                :success? true})
              {:status :valid
               :data data
               :schema nil
               :metadata metadata})))
        (catch Exception e
          (metrics/record-kafka-consume!
           {:topic topic
            :schema schema-name
            :duration-ms (timer)
            :success? false})
          {:status :invalid
           :error (.getMessage e)
           :raw-data (.value record)
           :metadata metadata})))))

(defrecord ArcNetConsumer [^KafkaConsumer kafka-consumer
                           dead-letter-producer
                           config]
  IArcNetConsumer

  (subscribe! [_ topics]
    (let [topic-list (if (string? topics) [topics] (vec topics))]
      (.subscribe kafka-consumer topic-list)
      (log/info "Subscribed to topics" {:topics topic-list})))

  (poll! [this timeout-ms]
    (let [^ConsumerRecords records (.poll kafka-consumer
                                          (Duration/ofMillis timeout-ms))
          results (for [^ConsumerRecord record (iterator-seq (.iterator records))]
                    (let [result (process-record record)]
                      ;; Send invalid messages to dead-letter
                      (when (and (= :invalid (:status result))
                                 dead-letter-producer)
                        (let [original-topic (get-in result [:metadata :topic])
                              dl-topic (dead-letter-topic original-topic)
                              error-headers (make-headers
                                             (merge
                                              (get-in result [:metadata :headers])
                                              {header-original-topic original-topic
                                               header-error-message
                                               (str (:error result))}))]
                          (log/warn "Sending invalid message to dead-letter"
                                    {:original-topic original-topic
                                     :dead-letter-topic dl-topic
                                     :error (:error result)})
                          (send-raw! dead-letter-producer
                                     dl-topic
                                     nil
                                     (:raw-data result)
                                     error-headers)))
                      result))]
      (doall results)))

  (commit! [_]
    (.commitSync kafka-consumer))

  (close! [_]
    (.close kafka-consumer)
    (when dead-letter-producer
      (close! dead-letter-producer))))

(defn create-consumer
  "Creates an ARCNet consumer with validation and dead-letter support.

   Config options:
   - :bootstrap-servers - Required
   - :group-id - Required
   - :client-id - Optional
   - :dead-letter-producer - Optional producer for invalid messages
   - :create-dead-letter-producer? - If true, creates internal DL producer"
  [{:keys [dead-letter-producer create-dead-letter-producer?]
    :as config}]
  (let [props (consumer-config config)
        consumer (KafkaConsumer. props)
        dl-producer (cond
                      dead-letter-producer dead-letter-producer
                      create-dead-letter-producer?
                      (create-producer
                       (assoc config :client-id
                              (str (:client-id config) "-dead-letter")))
                      :else nil)]
    (log/info "Created ARCNet consumer"
              {:bootstrap-servers (:bootstrap-servers config)
               :group-id (:group-id config)
               :dead-letter-enabled? (some? dl-producer)})
    (->ArcNetConsumer consumer dl-producer config)))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn with-producer
  "Executes f with a producer, ensuring cleanup."
  [config f]
  (let [producer (create-producer config)]
    (try
      (f producer)
      (finally
        (close! producer)))))

(defn with-consumer
  "Executes f with a consumer, ensuring cleanup."
  [config f]
  (let [consumer (create-consumer config)]
    (try
      (f consumer)
      (finally
        (close! consumer)))))

;; =============================================================================
;; Message Routing by Schema Version
;; =============================================================================

(defn route-by-version
  "Routes messages to handlers based on schema version.

   handlers is a map of version -> handler-fn
   Returns a function that processes poll results."
  [handlers & {:keys [default-handler]}]
  (fn [records]
    (for [{:keys [status data metadata schema] :as record} records
          :when (= :valid status)]
      (let [version (:schema/version data)
            handler (or (get handlers version)
                        default-handler)]
        (if handler
          (try
            {:status :processed
             :result (handler data metadata)
             :metadata metadata}
            (catch Exception e
              {:status :handler-error
               :error (.getMessage e)
               :data data
               :metadata metadata}))
          {:status :no-handler
           :version version
           :data data
           :metadata metadata})))))
