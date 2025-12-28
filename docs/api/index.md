# ArcNet API Reference

## Overview

ArcNet provides multiple API interfaces for interacting with the distributed compute network:

1. **WebSocket API** - Real-time telemetry streaming for the console
2. **Kafka API** - Message-driven integration for backend services
3. **REST API** - HTTP endpoints for queries and operations (future)
4. **gRPC API** - High-performance RPC for node communication (future)

## WebSocket API

### Connection

**Endpoint**: `ws://{aggregator-host}:8080/telemetry`

**Authentication**: API key in query parameter (production) or open (development)

```javascript
const ws = new WebSocket('ws://localhost:8080/telemetry?apiKey=your-key');

ws.onopen = () => {
  console.log('Connected to ArcNet telemetry stream');
};

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  console.log('Received:', message);
};

ws.onerror = (error) => {
  console.error('WebSocket error:', error);
};

ws.onclose = () => {
  console.log('Disconnected from ArcNet');
};
```

### Message Types

#### 1. Telemetry Update

Sent when a node's telemetry is updated (typically every 10 seconds).

```typescript
{
  type: 'telemetry',
  node: {
    id: string,              // Node UUID
    geohash: string,         // 6-character geohash
    energySource: 'cogen' | 'grid' | 'battery',
    batteryLevel: number,    // 0.0 to 1.0
    gpuUtilization: number,  // 0.0 to 1.0
    gpuMemoryFreeGb: number,
    modelsLoaded: string[],
    timestamp: string        // ISO 8601
  }
}
```

**Example**:
```json
{
  "type": "telemetry",
  "node": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "geohash": "9q9hvu",
    "energySource": "cogen",
    "batteryLevel": 0.87,
    "gpuUtilization": 0.42,
    "gpuMemoryFreeGb": 32.5,
    "modelsLoaded": ["llama-3-70b", "stable-diffusion-xl"],
    "timestamp": "2025-12-28T18:45:00Z"
  }
}
```

#### 2. Inference Event

Sent when an inference request is dispatched, completed, or fails.

```typescript
{
  type: 'inference',
  requestId: string,
  source: [number, number],    // [longitude, latitude]
  targetNodeId: string,
  modelId: string,
  priority: 'critical' | 'normal' | 'background',
  status: 'dispatched' | 'processing' | 'completed' | 'failed' | 'rejected',
  latencyMs?: number,          // Only present when completed
  timestamp: string
}
```

**Example**:
```json
{
  "type": "inference",
  "requestId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "source": [-122.4194, 37.7749],
  "targetNodeId": "550e8400-e29b-41d4-a716-446655440000",
  "modelId": "llama-3-70b",
  "priority": "normal",
  "status": "completed",
  "latencyMs": 245,
  "timestamp": "2025-12-28T18:45:15Z"
}
```

#### 3. HPC Transfer Event

Sent when an HPC dataset transfer is initiated, progresses, or completes.

```typescript
{
  type: 'hpc',
  jobId: string,
  sourceNodeId: string,
  datasetSizeGb: number,
  status: 'pending' | 'transferring' | 'queued' | 'running' | 'completed' | 'failed',
  progress: number,            // 0.0 to 1.0
  bytesTransferred: number,
  estimatedCompletionTime?: string,
  timestamp: string
}
```

**Example**:
```json
{
  "type": "hpc",
  "jobId": "hpc-job-12345",
  "sourceNodeId": "550e8400-e29b-41d4-a716-446655440000",
  "datasetSizeGb": 150.5,
  "status": "transferring",
  "progress": 0.65,
  "bytesTransferred": 101687091200,
  "estimatedCompletionTime": "2025-12-28T19:30:00Z",
  "timestamp": "2025-12-28T18:45:30Z"
}
```

#### 4. System Event

Sent for system-level events, alerts, and status changes.

```typescript
{
  type: 'system',
  severity: 'info' | 'warn' | 'error' | 'success',
  message: string,
  details?: object,
  nodeId?: string,
  timestamp: string
}
```

**Example**:
```json
{
  "type": "system",
  "severity": "warn",
  "message": "Low battery detected on node CAISO-SanJose-042",
  "details": {
    "batteryLevel": 0.15,
    "energySource": "battery"
  },
  "nodeId": "550e8400-e29b-41d4-a716-446655440000",
  "timestamp": "2025-12-28T18:46:00Z"
}
```

### Client-to-Server Messages

#### Subscribe to Geozone

Filter telemetry updates to specific geozones.

```typescript
{
  type: 'subscribe',
  geozones: string[]
}
```

**Example**:
```json
{
  "type": "subscribe",
  "geozones": ["CAISO", "ERCOT"]
}
```

#### Heartbeat

Keep connection alive (sent automatically by client every 30 seconds).

```typescript
{
  type: 'ping'
}
```

**Response**:
```json
{
  "type": "pong",
  "timestamp": "2025-12-28T18:47:00Z"
}
```

## Kafka API

### Topics

#### arc.telemetry.node

**Purpose**: Node telemetry updates

**Schema**: NodeTelemetry v2

**Key**: Node ID (UUID as string)

**Partitioning**: By geozone hash

**Retention**: 7 days

**Example Message**:
```clojure
{:schema/version 2
 :id #uuid "550e8400-e29b-41d4-a716-446655440000"
 :geohash "9q9hvu"
 :energy-source :cogen
 :battery-level 0.87
 :gpu-utilization 0.42
 :gpu-memory-free-gb 32.5
 :models-loaded ["llama-3-70b" "stable-diffusion-xl"]
 :timestamp #inst "2025-12-28T18:45:00Z"}
```

#### arc.request.inference

**Purpose**: Inference request submissions

**Schema**: InferenceRequest v1

**Key**: Request ID (UUID as string)

**Partitioning**: By requester geozone

**Retention**: 24 hours

**Example Message**:
```clojure
{:schema/version 1
 :id #uuid "7c9e6679-7425-40de-944b-e07fc1f90ae7"
 :model-id "llama-3-70b"
 :context-window-tokens 4096
 :priority :normal
 :max-latency-ms 500
 :requester-geozone "CAISO"}
```

#### arc.command.dispatch.{geozone}

**Purpose**: Inference assignments to nodes

**Schema**: InferenceDispatch v1

**Key**: Request ID (UUID as string)

**Partitioning**: Single partition per geozone

**Retention**: 1 hour

**Example Message**:
```clojure
{:schema/version 1
 :request-id #uuid "7c9e6679-7425-40de-944b-e07fc1f90ae7"
 :node-id #uuid "550e8400-e29b-41d4-a716-446655440000"
 :model-id "llama-3-70b"
 :context-window-tokens 4096
 :dispatched-at #inst "2025-12-28T18:45:10Z"}
```

#### arc.result.inference

**Purpose**: Inference completion results

**Schema**: InferenceResult v1

**Key**: Request ID (UUID as string)

**Partitioning**: By geozone hash

**Retention**: 7 days

**Example Message**:
```clojure
{:schema/version 1
 :request-id #uuid "7c9e6679-7425-40de-944b-e07fc1f90ae7"
 :node-id #uuid "550e8400-e29b-41d4-a716-446655440000"
 :status :completed
 :latency-ms 245
 :completed-at #inst "2025-12-28T18:45:15Z"}
```

### Message Headers

All Kafka messages include the following headers:

| Header | Description | Example |
|--------|-------------|---------|
| `arcnet-schema-version` | Schema version number | `2` |
| `arcnet-entity-type` | Entity type name | `NodeTelemetry` |
| `arcnet-geozone` | Source geozone | `CAISO` |
| `traceparent` | W3C trace context | `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01` |

### Producer Example (Clojure)

```clojure
(require '[arcnet.transport.kafka :as kafka])

(def producer (kafka/create-producer
                {:bootstrap-servers "localhost:9092"
                 :client-id "my-service"}))

(kafka/send! producer
             "arc.telemetry.node"
             :node-telemetry
             {:id (java.util.UUID/randomUUID)
              :geohash "9q9hvu"
              :energy-source :cogen
              :battery-level 0.87
              :gpu-utilization 0.42
              :gpu-memory-free-gb 32.5
              :models-loaded ["llama-3-70b"]
              :timestamp (java.time.Instant/now)})
```

### Consumer Example (Clojure)

```clojure
(require '[arcnet.transport.kafka :as kafka])

(def consumer (kafka/create-consumer
                {:bootstrap-servers "localhost:9092"
                 :group-id "my-consumer-group"
                 :create-dead-letter-producer? true}))

(kafka/subscribe! consumer ["arc.telemetry.node"])

(loop []
  (let [records (kafka/poll! consumer 1000)]
    (doseq [record records]
      (case (:status record)
        :valid (process-telemetry (:data record))
        :invalid (log/error "Invalid message" (:error record))))
    (kafka/commit! consumer)
    (recur)))
```

## REST API (Future)

### GET /api/v1/nodes

List all nodes with optional filtering.

**Query Parameters**:
- `geozone` - Filter by geozone
- `status` - Filter by status (online, busy, idle, stale, offline)
- `energy` - Filter by energy source (cogen, grid, battery)
- `limit` - Maximum results (default: 100)
- `offset` - Pagination offset

**Response**:
```json
{
  "nodes": [...],
  "total": 150,
  "limit": 100,
  "offset": 0
}
```

### GET /api/v1/nodes/{id}

Get detailed information about a specific node.

### POST /api/v1/inference

Submit an inference request.

### GET /api/v1/stats

Get global network statistics.

## Next Steps

- **[Protocol Reference](../protocol/index.md)** - Backend implementation details
- **[Console Guide](../console/index.md)** - Frontend integration
- **[Schema Reference](../protocol/schemas.md)** - Complete schema definitions

