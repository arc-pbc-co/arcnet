# ArcNet Architecture

## System-of-Systems Design

ArcNet employs a hierarchical, federated architecture that balances autonomy with coordination. The system is designed to scale from dozens to thousands of compute nodes while maintaining low-latency inference routing and efficient resource utilization.

## Architecture Layers

### Layer 1: Compute Nodes (Ganglions)

**Purpose**: Execute AI inference and participate in federated training

**Characteristics**:
- 1MW power capacity per node
- Multiple GPUs (typically 4-8 per node)
- Local battery storage for grid independence
- Co-located generation (COGEN) or grid power
- Geohash-based location encoding

**Responsibilities**:
- Execute inference requests for loaded models
- Report telemetry (battery, GPU utilization, energy source)
- Participate in federated training when assigned
- Maintain model cache and handle model loading

**Communication**:
- Publish telemetry to `arc.telemetry.node` Kafka topic
- Subscribe to `arc.command.dispatch.{geozone}` for inference assignments
- Subscribe to `arc.scheduler.training` for training tasks

### Layer 2: Regional Aggregators (Geozones)

**Purpose**: Coordinate nodes within a geographic region and maintain regional state

**Characteristics**:
- One aggregator per geozone (e.g., CAISO, ERCOT, PJM)
- Embedded XTDB v2 database for regional state
- Kafka broker for event streaming
- Prometheus metrics endpoint

**Responsibilities**:
- Aggregate telemetry from nodes in the region
- Maintain real-time view of node availability and capabilities
- Route inference requests to optimal nodes within the region
- Publish regional summaries to `arc.summary.regional` topic
- Handle cross-region request forwarding

**State Management**:
```clojure
;; XTDB Document Structure
{:xt/id "node-uuid"
 :node/name "CAISO-SanJose-042"
 :node/geohash "9q9hvu"
 :node/geozone "CAISO"
 :node/energy-source :cogen
 :node/battery-level 0.87
 :node/gpu-utilization 0.42
 :node/models-loaded ["llama-3-70b" "stable-diffusion-xl"]
 :node/status :online
 :node/last-seen #inst "2025-12-28T18:45:00Z"}
```

### Layer 3: Central Orchestrator (ORNL Brain)

**Purpose**: Global coordination, HPC integration, and long-term state management

**Characteristics**:
- Hosted at Oak Ridge National Laboratory
- Access to Frontier/Lux supercomputers
- Tier-2 XTDB database with full network history
- Globus endpoints for data transfer

**Responsibilities**:
- Aggregate regional summaries into global view
- Classify training jobs (HPC vs. federated)
- Orchestrate data movement to HPC facilities
- Maintain historical telemetry and performance metrics
- Provide global query interface for analytics

**HPC Integration**:
- Receive training job submissions on `arc.job.submission`
- Classify based on FLOPS, dataset size, and urgency
- Route to `ornl.bridge.ingress` for HPC jobs
- Route to `arc.scheduler.training` for federated jobs
- Monitor Globus transfer status

## Data Flow Patterns

### Pattern 1: Inference Request Routing

```
1. Client → arc.request.inference
   {request-id, model-id, context-window, priority, requester-geozone}

2. Regional Scheduler queries XTDB for candidates:
   - Same geozone preferred
   - Model loaded
   - GPU utilization < 85%
   - Battery level > 20% (if battery-powered)
   - Energy source preference: cogen > grid > battery

3. Scheduler attempts reservation:
   - Optimistic lock on node document
   - Set reservation with expiry (30s)

4. On success → arc.command.dispatch.{geozone}
   {request-id, node-id, model-id, context-window}

5. Node executes inference, publishes result:
   arc.result.inference
   {request-id, node-id, latency-ms, status}

6. Scheduler releases reservation
```

### Pattern 2: Telemetry Streaming

```
1. Node publishes telemetry every 10s:
   arc.telemetry.node
   {node-id, battery-level, gpu-utilization, energy-source, models-loaded}

2. Regional Aggregator consumes and updates XTDB:
   - Upsert node document
   - Update last-seen timestamp
   - Trigger status change events if needed

3. Aggregator publishes regional summary every 60s:
   arc.summary.regional
   {geozone-id, total-nodes, active-nodes, avg-gpu-util, energy-breakdown}

4. Central Orchestrator consumes summaries:
   - Update global statistics
   - Detect anomalies
   - Trigger alerts if needed

5. Console subscribes to WebSocket endpoint:
   - Receives filtered telemetry updates
   - Receives inference events
   - Receives HPC transfer status
```

### Pattern 3: HPC Training Offload

```
1. Training job submitted:
   arc.job.submission
   {job-id, dataset-uri, dataset-size-gb, estimated-flops, checkpoint-uri}

2. Bridge Classifier evaluates:
   - FLOPS > 1e18 → HPC
   - Dataset size > 100GB → HPC
   - Urgency = critical → HPC
   - Otherwise → Federated

3. For HPC jobs:
   a. Publish to arc.bridge.pending
   b. Data Mover initiates Globus transfer
   c. Monitor transfer progress
   d. On completion → ornl.bridge.ingress
   e. Submit to Frontier/Lux queue

4. For Federated jobs:
   a. Publish to arc.scheduler.training
   b. Partition dataset across nodes
   c. Coordinate training rounds
   d. Aggregate gradients
   e. Publish checkpoints
```

## Communication Protocols

### Kafka Topics

| Topic | Purpose | Schema | Retention |
|-------|---------|--------|-----------|
| `arc.telemetry.node` | Node telemetry updates | NodeTelemetry v2 | 7 days |
| `arc.request.inference` | Inference requests | InferenceRequest v1 | 24 hours |
| `arc.command.dispatch.{geozone}` | Inference assignments | InferenceDispatch v1 | 1 hour |
| `arc.result.inference` | Inference results | InferenceResult v1 | 7 days |
| `arc.job.submission` | Training job submissions | TrainingJob v1 | 30 days |
| `arc.bridge.pending` | HPC jobs awaiting transfer | HpcJob v1 | 30 days |
| `ornl.bridge.ingress` | Jobs ready for ORNL | HpcJob v1 | 90 days |
| `arc.scheduler.training` | Federated training tasks | FederatedTask v1 | 30 days |
| `arc.summary.regional` | Regional aggregates | RegionalSummary v1 | 30 days |
| `arc.event.system` | System events and alerts | SystemEvent v1 | 90 days |

### WebSocket Protocol

The ArcNet Console connects to a WebSocket endpoint for real-time updates.

**Connection**: `ws://aggregator-host:8080/telemetry`

**Message Types**:

```typescript
// Telemetry Update
{
  type: 'telemetry',
  node: {
    id: string,
    geohash: string,
    energySource: 'cogen' | 'grid' | 'battery',
    batteryLevel: number,
    gpuUtilization: number,
    modelsLoaded: string[],
    timestamp: string
  }
}

// Inference Event
{
  type: 'inference',
  requestId: string,
  source: [number, number],  // [lng, lat]
  targetNodeId: string,
  modelId: string,
  priority: 'critical' | 'normal' | 'background',
  status: 'dispatched' | 'processing' | 'completed' | 'failed',
  latencyMs?: number,
  timestamp: string
}

// HPC Transfer Event
{
  type: 'hpc',
  jobId: string,
  sourceNodeId: string,
  datasetSizeGb: number,
  status: 'pending' | 'transferring' | 'queued' | 'running' | 'completed',
  progress: number,
  bytesTransferred: number,
  timestamp: string
}

// System Event
{
  type: 'system',
  severity: 'info' | 'warn' | 'error' | 'success',
  message: string,
  details?: object,
  timestamp: string
}
```

## Scalability Considerations

### Horizontal Scaling
- **Nodes**: Add nodes to any geozone without coordination
- **Aggregators**: Deploy multiple aggregators per geozone with Kafka consumer groups
- **Kafka**: Partition topics by geozone for parallel processing

### Fault Tolerance
- **Node Failures**: Detected via telemetry timeout (30s), requests rerouted
- **Aggregator Failures**: Kafka consumer group rebalancing
- **Network Partitions**: Regional autonomy allows continued operation

### Performance Targets
- **Inference Latency**: P99 < 500ms (including routing)
- **Telemetry Lag**: < 15s from node to console
- **Throughput**: 10,000 inference requests/second per aggregator
- **Node Capacity**: 1,000 nodes per aggregator

## Security Model

### Authentication
- **mTLS**: All inter-component communication
- **API Keys**: Console WebSocket connections
- **Globus Auth**: HPC data transfers

### Authorization
- **Role-Based**: Operators, developers, read-only viewers
- **Geozone Isolation**: Nodes can only access their geozone's topics
- **Audit Logging**: All state mutations logged to XTDB

### Data Protection
- **Encryption in Transit**: TLS 1.3 for all network communication
- **Encryption at Rest**: XTDB data directory encryption
- **PII Handling**: No personally identifiable information in telemetry

## Next Steps

- **[Getting Started](getting-started.md)** - Set up a local development environment
- **[Protocol Reference](protocol/index.md)** - Detailed backend documentation
- **[Console Guide](console/index.md)** - Operations dashboard usage
- **[API Reference](api/index.md)** - WebSocket and REST API specifications

