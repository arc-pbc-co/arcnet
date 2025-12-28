# ArcNet Protocol

## Overview

The ArcNet Protocol is the backend orchestration system that coordinates distributed AI inference and training across the mesh network. Built with Clojure, it provides robust message-driven architecture with strong consistency guarantees, comprehensive observability, and seamless HPC integration.

## Core Components

### 1. Transport Layer

**Purpose**: Reliable message passing with schema validation

**Technologies**:
- Apache Kafka for message brokering
- Transit + MessagePack for serialization
- Malli for schema validation

**Features**:
- Schema versioning in message headers
- Automatic dead-letter queue for invalid messages
- Prometheus metrics for produce/consume operations
- OpenTelemetry distributed tracing
- Exactly-once semantics for critical operations

**Key Modules**:
- `arcnet.transport.kafka` - Kafka producer/consumer wrappers
- `arcnet.transport.serialization` - Transit serialization
- `arcnet.schema.registry` - Malli schema definitions

### 2. State Management

**Purpose**: Maintain consistent view of network state

**Technologies**:
- XTDB v2 for bitemporal, immutable database
- Embedded mode for regional aggregators
- Distributed mode for central orchestrator

**Features**:
- Bitemporal queries (valid-time and transaction-time)
- Immutable audit trail
- Datalog query language
- Automatic indexing
- Point-in-time snapshots

**Key Modules**:
- `arcnet.state.regional` - Regional aggregator state
- `arcnet.state.aggregator` - Summary computation
- `arcnet.state.queries` - Common query patterns

### 3. Scheduler

**Purpose**: Route inference requests to optimal nodes

**Algorithm**:
1. Query XTDB for candidate nodes:
   - Same geozone preferred (minimize latency)
   - Model loaded (avoid cold start)
   - GPU utilization < 85% (capacity available)
   - Battery level > 20% (if battery-powered)
   - Energy source preference: COGEN > grid > battery

2. Score candidates:
   ```clojure
   (defn score-candidate [node request]
     (+ (* 100 (if (= (:geozone node) (:requester-geozone request)) 1 0))
        (* 50 (if (= (:energy-source node) :cogen) 1 0))
        (* 30 (- 1.0 (:gpu-utilization node)))
        (* 20 (:battery-level node))))
   ```

3. Attempt reservation (optimistic lock):
   ```clojure
   (xt/submit-tx node
     [[:put {:xt/id node-id
             :reservation {:request-id request-id
                          :expires-at (+ (now) 30000)}}]])
   ```

4. On success, dispatch to node
5. On failure, retry with next candidate

**Key Modules**:
- `arcnet.scheduler.core` - Main scheduling loop
- `arcnet.scheduler.scoring` - Candidate scoring
- `arcnet.scheduler.reservation` - Reservation management

### 4. Bridge Orchestrator

**Purpose**: Classify and route training jobs to HPC or federated mesh

**Classification Logic**:
```clojure
(defn classify-job [job]
  (cond
    (> (:estimated-flops job) 1e18) :hpc
    (> (:dataset-size-gb job) 100) :hpc
    (= (:urgency job) :critical) :hpc
    :else :federated))
```

**HPC Integration**:
- Globus-based data transfer
- mTLS authentication
- Transfer progress monitoring
- Automatic retry on failure

**Key Modules**:
- `arcnet.bridge.orchestrator` - Main orchestration loop
- `arcnet.bridge.classifier` - Job classification
- `arcnet.bridge.data-mover` - Globus integration

### 5. Observability

**Purpose**: Comprehensive monitoring and tracing

**Metrics** (Prometheus):
- `arcnet_nodes_total{geozone, status}` - Node count by status
- `arcnet_gpu_utilization_avg{geozone}` - Average GPU utilization
- `arcnet_inference_requests_total{geozone, status}` - Request count
- `arcnet_inference_latency_ms{geozone, quantile}` - Latency histogram
- `arcnet_kafka_produce_duration_ms{topic}` - Kafka produce latency
- `arcnet_xtdb_query_duration_ms{query_type}` - XTDB query latency

**Tracing** (OpenTelemetry):
- Distributed traces across Kafka topics
- Span context propagation in message headers
- Integration with Jaeger/Zipkin

**Key Modules**:
- `arcnet.observability.metrics` - Prometheus metrics
- `arcnet.observability.tracing` - OpenTelemetry tracing
- `arcnet.observability.logging` - Structured logging

## Message Schemas

### NodeTelemetry v2

```clojure
(def NodeTelemetry
  [:map
   [:schema/version [:= 2]]
   [:id :uuid]
   [:geohash [:string {:min 6 :max 6}]]
   [:energy-source [:enum :cogen :grid :battery]]
   [:battery-level [:double {:min 0.0 :max 1.0}]]
   [:gpu-utilization [:double {:min 0.0 :max 1.0}]]
   [:gpu-memory-free-gb [:double {:min 0}]]
   [:models-loaded [:vector :string]]
   [:timestamp :inst]])
```

### InferenceRequest v1

```clojure
(def InferenceRequest
  [:map
   [:schema/version [:= 1]]
   [:id :uuid]
   [:model-id :string]
   [:context-window-tokens [:int {:min 1}]]
   [:priority [:enum :critical :normal :background]]
   [:max-latency-ms [:int {:min 1}]]
   [:requester-geozone :string]])
```

### InferenceDispatch v1

```clojure
(def InferenceDispatch
  [:map
   [:schema/version [:= 1]]
   [:request-id :uuid]
   [:node-id :uuid]
   [:model-id :string]
   [:context-window-tokens [:int {:min 1}]]
   [:dispatched-at :inst]])
```

### TrainingJob v1

```clojure
(def TrainingJob
  [:map
   [:schema/version [:= 1]]
   [:id :uuid]
   [:dataset-uri :string]
   [:dataset-size-gb [:double {:min 0}]]
   [:estimated-flops [:double {:min 0}]]
   [:checkpoint-uri {:optional true} :string]
   [:target [:enum :hpc :federated]]
   [:routing-reason :string]])
```

## Configuration

### config.edn

```clojure
{:kafka
 {:bootstrap-servers "localhost:9092"
  :client-id "arcnet-aggregator"
  :acks "all"
  :retries 3}
 
 :xtdb
 {:data-dir "/var/lib/arcnet/xtdb"
  :geozone-id "CAISO"}
 
 :scheduler
 {:poll-interval-ms 100
  :reservation-timeout-ms 30000
  :max-retries 3}
 
 :bridge
 {:globus-endpoint "ornl-frontier"
  :transfer-timeout-ms 3600000}
 
 :observability
 {:prometheus-port 9090
  :otel-endpoint "http://localhost:4317"
  :log-level :info}}
```

### Environment Variables

```bash
# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# XTDB
XTDB_DATA_DIR=/var/lib/arcnet/xtdb
GEOZONE_ID=CAISO

# Observability
PROMETHEUS_PORT=9090
OTEL_EXPORTER_ENDPOINT=http://localhost:4317
LOG_LEVEL=info

# Security
KAFKA_SECURITY_PROTOCOL=SASL_SSL
KAFKA_SASL_MECHANISM=PLAIN
KAFKA_SASL_USERNAME=arcnet
KAFKA_SASL_PASSWORD=secret
```

## Running Components

### Regional Aggregator

```bash
clojure -M:run aggregator --geozone CAISO
```

**Responsibilities**:
- Consume node telemetry
- Update XTDB state
- Publish regional summaries
- Serve WebSocket connections
- Expose Prometheus metrics

### Scheduler

```bash
clojure -M:run scheduler --geozone CAISO
```

**Responsibilities**:
- Consume inference requests
- Query XTDB for candidates
- Attempt reservations
- Dispatch to nodes
- Track results

### Bridge Orchestrator

```bash
clojure -M:run bridge
```

**Responsibilities**:
- Consume training job submissions
- Classify jobs (HPC vs. federated)
- Initiate Globus transfers
- Monitor transfer progress
- Submit to HPC queue

### Simulator

```bash
clojure -M:run simulator --nodes 20 --geozone CAISO
```

**Responsibilities**:
- Generate realistic node telemetry
- Simulate inference requests
- Vary battery levels and GPU utilization
- Publish to Kafka topics

## Development

### REPL-Driven Development

```clojure
;; Start REPL
clojure -M:repl

;; Load namespace
(require '[arcnet.state.regional :as regional])
(require '[arcnet.transport.kafka :as kafka])

;; Start XTDB
(regional/start-xtdb! {:geozone-id "CAISO"})

;; Query nodes
(regional/query-all-nodes)

;; Create producer
(def producer (kafka/create-producer
                {:bootstrap-servers "localhost:9092"
                 :client-id "repl-client"}))

;; Send test message
(kafka/send! producer
             "arc.telemetry.node"
             :node-telemetry
             {:id (java.util.UUID/randomUUID)
              :geohash "9q9hvu"
              :energy-source :cogen
              :battery-level 0.85
              :gpu-utilization 0.42
              :gpu-memory-free-gb 32.0
              :models-loaded ["llama-3-70b"]
              :timestamp (java.time.Instant/now)})
```

### Testing

```bash
# Run all tests
clojure -M:test

# Run specific namespace
clojure -M:test -n arcnet.scheduler.core-test

# Run with coverage
clojure -M:test:coverage
```

## Deployment

### Docker

```dockerfile
FROM clojure:temurin-21-tools-deps-alpine
WORKDIR /app
COPY deps.edn .
RUN clojure -P
COPY . .
CMD ["clojure", "-M:run", "aggregator"]
```

### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: arcnet-aggregator-caiso
spec:
  replicas: 2
  selector:
    matchLabels:
      app: arcnet-aggregator
      geozone: caiso
  template:
    metadata:
      labels:
        app: arcnet-aggregator
        geozone: caiso
    spec:
      containers:
      - name: aggregator
        image: arcnet/protocol:latest
        args: ["aggregator", "--geozone", "CAISO"]
        env:
        - name: KAFKA_BOOTSTRAP_SERVERS
          value: "kafka:9092"
        - name: XTDB_DATA_DIR
          value: "/data/xtdb"
        ports:
        - containerPort: 8080
          name: websocket
        - containerPort: 9090
          name: metrics
        volumeMounts:
        - name: xtdb-data
          mountPath: /data/xtdb
      volumes:
      - name: xtdb-data
        persistentVolumeClaim:
          claimName: xtdb-pvc-caiso
```

## Next Steps

- **[API Reference](../api/index.md)** - Detailed API documentation
- **[Schema Reference](schemas.md)** - Complete schema definitions
- **[Operations Guide](operations.md)** - Production operations
- **[Troubleshooting](troubleshooting.md)** - Common issues and solutions

