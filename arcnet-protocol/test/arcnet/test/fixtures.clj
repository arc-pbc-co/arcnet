(ns arcnet.test.fixtures
  "Test fixtures for ARCNet integration tests.

   Provides:
   - Kafka container management via testcontainers
   - XTDB embedded node for testing
   - Mock HTTP server for Globus API
   - Utility functions for test setup/teardown"
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [arcnet.transport.kafka :as kafka]
            [arcnet.transport.serialization :as ser]
            [arcnet.state.regional :as regional])
  (:import [org.testcontainers.kafka KafkaContainer]
           [org.testcontainers.utility DockerImageName]
           [org.apache.kafka.clients.admin AdminClient AdminClientConfig NewTopic]
           [java.util Properties]
           [java.time Duration]))

;; =============================================================================
;; Kafka Container
;; =============================================================================

(def ^:dynamic *kafka-container* nil)
(def ^:dynamic *kafka-bootstrap-servers* nil)

(defn start-kafka-container!
  "Starts a Kafka container and returns it."
  []
  (log/info "Starting Kafka testcontainer...")
  (let [container (doto (KafkaContainer.
                         (DockerImageName/parse "confluentinc/cp-kafka:7.6.1"))
                    (.withKraft)
                    (.start))]
    (log/info "Kafka container started"
              {:bootstrap-servers (.getBootstrapServers container)})
    container))

(defn stop-kafka-container!
  "Stops a Kafka container."
  [^KafkaContainer container]
  (when container
    (log/info "Stopping Kafka testcontainer...")
    (.stop container)
    (log/info "Kafka container stopped")))

(defn kafka-bootstrap-servers
  "Returns the bootstrap servers for the running Kafka container."
  [^KafkaContainer container]
  (.getBootstrapServers container))

(defn create-admin-client
  "Creates a Kafka AdminClient for the container."
  [bootstrap-servers]
  (let [props (doto (Properties.)
                (.put AdminClientConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers))]
    (AdminClient/create props)))

(defn create-topics!
  "Creates Kafka topics using AdminClient."
  [bootstrap-servers topics]
  (with-open [admin (create-admin-client bootstrap-servers)]
    (let [new-topics (map (fn [topic-name]
                            (NewTopic. topic-name 1 (short 1)))
                          topics)]
      @(.all (.createTopics admin new-topics))
      (log/info "Created topics" {:topics topics}))))

(defn delete-topics!
  "Deletes Kafka topics."
  [bootstrap-servers topics]
  (with-open [admin (create-admin-client bootstrap-servers)]
    @(.all (.deleteTopics admin topics))
    (log/info "Deleted topics" {:topics topics})))

;; =============================================================================
;; XTDB Embedded Node
;; =============================================================================

(def ^:dynamic *xtdb-node* nil)

(defn start-xtdb-node!
  "Starts an embedded XTDB node for testing."
  []
  (log/info "Starting embedded XTDB node for testing...")
  (regional/start-xtdb! {:storage-dir (str "/tmp/arcnet-test-xtdb-" (System/currentTimeMillis))})
  (log/info "XTDB test node started"))

(defn stop-xtdb-node!
  "Stops the XTDB test node."
  []
  (log/info "Stopping XTDB test node...")
  (regional/stop-xtdb!)
  (log/info "XTDB test node stopped"))

;; =============================================================================
;; Mock Globus API Server
;; =============================================================================

(def ^:dynamic *mock-globus-server* nil)
(def ^:dynamic *mock-globus-port* nil)
(def ^:dynamic *globus-requests* nil)

(defn create-mock-globus-handler
  "Creates a Ring handler that mocks the Globus Transfer API."
  [requests-atom]
  (fn [request]
    ;; Record all requests
    (swap! requests-atom conj {:method (:request-method request)
                               :uri (:uri request)
                               :body (when-let [body (:body request)]
                                       (slurp body))
                               :timestamp (System/currentTimeMillis)})
    (let [uri (:uri request)
          method (:request-method request)]
      (cond
        ;; OAuth token endpoint
        (and (= :post method) (= "/v2/oauth2/token" uri))
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body "{\"access_token\":\"mock-token-12345\",\"token_type\":\"Bearer\",\"expires_in\":3600}"}

        ;; Transfer submission
        (and (= :post method) (= "/v0.10/transfer" uri))
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (str "{\"task_id\":\"mock-task-" (java.util.UUID/randomUUID) "\","
                    "\"code\":\"Accepted\","
                    "\"message\":\"Transfer submitted successfully\"}")}

        ;; Task status
        (and (= :get method) (re-matches #"/v0.10/task/.*" uri))
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body "{\"status\":\"SUCCEEDED\",\"bytes_transferred\":1000000,\"files_transferred\":10}"}

        ;; Default 404
        :else
        {:status 404
         :headers {"Content-Type" "application/json"}
         :body "{\"error\":\"Not found\"}"}))))

(defn start-mock-globus-server!
  "Starts a mock Globus API server.

   Returns the server and port."
  []
  (require 'ring.adapter.jetty)
  (let [requests-atom (atom [])
        handler (create-mock-globus-handler requests-atom)
        ;; Find an available port
        server-socket (java.net.ServerSocket. 0)
        port (.getLocalPort server-socket)
        _ (.close server-socket)
        ;; Start Jetty
        server ((resolve 'ring.adapter.jetty/run-jetty)
                handler
                {:port port :join? false})]
    (log/info "Started mock Globus server" {:port port})
    {:server server
     :port port
     :requests requests-atom}))

(defn stop-mock-globus-server!
  "Stops the mock Globus server."
  [{:keys [server]}]
  (when server
    (.stop server)
    (log/info "Stopped mock Globus server")))

(defn get-globus-requests
  "Returns all recorded requests to the mock Globus server."
  []
  (when *globus-requests*
    @*globus-requests*))

(defn clear-globus-requests!
  "Clears recorded Globus requests."
  []
  (when *globus-requests*
    (reset! *globus-requests* [])))

;; =============================================================================
;; Test Fixture Composition
;; =============================================================================

(defn with-kafka
  "Test fixture that provides a Kafka container.

   Usage:
   (use-fixtures :once with-kafka)"
  [f]
  (let [container (start-kafka-container!)]
    (try
      (binding [*kafka-container* container
                *kafka-bootstrap-servers* (kafka-bootstrap-servers container)]
        (f))
      (finally
        (stop-kafka-container! container)))))

(defn with-xtdb
  "Test fixture that provides an XTDB node.

   Usage:
   (use-fixtures :once with-xtdb)"
  [f]
  (start-xtdb-node!)
  (try
    (binding [*xtdb-node* (regional/get-node)]
      (f))
    (finally
      (stop-xtdb-node!))))

(defn with-mock-globus
  "Test fixture that provides a mock Globus API server.

   Usage:
   (use-fixtures :once with-mock-globus)"
  [f]
  (let [{:keys [server port requests]} (start-mock-globus-server!)]
    (try
      (binding [*mock-globus-server* server
                *mock-globus-port* port
                *globus-requests* requests]
        (f))
      (finally
        (stop-mock-globus-server! {:server server})))))

(defn with-full-stack
  "Test fixture that provides Kafka, XTDB, and mock Globus.

   Usage:
   (use-fixtures :once with-full-stack)"
  [f]
  (with-kafka
    (fn []
      (with-xtdb
        (fn []
          (with-mock-globus f))))))

;; =============================================================================
;; Test Utilities
;; =============================================================================

(defn make-test-kafka-config
  "Creates Kafka configuration for tests."
  [& {:keys [group-id client-id]
      :or {group-id "test-group"
           client-id "test-client"}}]
  {:bootstrap-servers *kafka-bootstrap-servers*
   :group-id group-id
   :client-id client-id})

(defn wait-for-messages
  "Waits for messages on a topic, with timeout.

   Returns collected messages or throws on timeout."
  [consumer expected-count timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        collected (atom [])]
    (loop []
      (when (>= (System/currentTimeMillis) deadline)
        (throw (ex-info "Timeout waiting for messages"
                        {:expected expected-count
                         :received (count @collected)
                         :messages @collected})))
      (let [records (kafka/poll! consumer 100)]
        (swap! collected into records)
        (if (>= (count @collected) expected-count)
          @collected
          (recur))))))

(defn wait-for-condition
  "Waits for a condition to become true, with timeout.

   pred is a zero-arg function that returns truthy when done."
  [pred timeout-ms & {:keys [poll-interval-ms] :or {poll-interval-ms 50}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do
                (Thread/sleep poll-interval-ms)
                (recur))))))

(defn eventually
  "Assertion helper - retries assertion until it passes or times out."
  [assertion-fn timeout-ms & {:keys [poll-interval-ms] :or {poll-interval-ms 50}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        last-error (atom nil)]
    (loop []
      (try
        (assertion-fn)
        (catch Throwable t
          (reset! last-error t)
          (if (>= (System/currentTimeMillis) deadline)
            (throw (ex-info "Assertion timed out"
                            {:timeout-ms timeout-ms
                             :last-error @last-error}
                            @last-error))
            (do
              (Thread/sleep poll-interval-ms)
              (recur))))))))

;; =============================================================================
;; Test Data Generators
;; =============================================================================

(defn generate-test-node
  "Generates a test node telemetry record."
  [& {:keys [id geohash energy-source battery-level gpu-utilization
             gpu-memory-free-gb models-loaded]
      :or {geohash "9q8yyk"
           energy-source :solar
           battery-level 0.85
           gpu-utilization 0.3
           gpu-memory-free-gb 32.0
           models-loaded ["llama-3.1-8b"]}}]
  {:schema/version 2
   :id (or id (java.util.UUID/randomUUID))
   :timestamp (java.util.Date.)
   :geohash geohash
   :energy-source energy-source
   :battery-level battery-level
   :gpu-utilization gpu-utilization
   :gpu-memory-free-gb gpu-memory-free-gb
   :models-loaded (vec models-loaded)})

(defn generate-test-inference-request
  "Generates a test inference request."
  [& {:keys [id model-id context-window-tokens priority max-latency-ms requester-geozone]
      :or {model-id "llama-3.1-8b"
           context-window-tokens 1024
           priority :normal
           max-latency-ms 500
           requester-geozone "9q8yyk"}}]
  {:schema/version 2
   :id (or id (java.util.UUID/randomUUID))
   :model-id model-id
   :context-window-tokens context-window-tokens
   :priority priority
   :max-latency-ms max-latency-ms
   :requester-geozone requester-geozone})

(defn generate-test-training-job
  "Generates a test training job."
  [& {:keys [id dataset-uri dataset-size-gb estimated-flops checkpoint-uri]
      :or {dataset-uri "s3://arcnet-data/training/dataset-001"
           dataset-size-gb 500.0
           estimated-flops 1e15}}]
  (cond-> {:schema/version 2
           :id (or id (java.util.UUID/randomUUID))
           :dataset-uri dataset-uri
           :dataset-size-gb dataset-size-gb
           :estimated-flops estimated-flops}
    checkpoint-uri (assoc :checkpoint-uri checkpoint-uri)))

(defn seed-nodes!
  "Seeds XTDB with test nodes.

   Returns the seeded node IDs."
  [nodes]
  (let [xtdb (regional/get-node)]
    (doseq [node nodes]
      (let [node-id (:id node)
            doc (merge node
                       {:xt/id node-id
                        :node/geohash (:geohash node)
                        :node/last-seen (java.util.Date.)})]
        (.submitTx xtdb
                   (into-array [(xtdb.api.tx.TxOps/put node-id doc)]))))
    ;; Wait for indexing
    (Thread/sleep 100)
    (mapv :id nodes)))
