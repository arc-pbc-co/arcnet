# Getting Started with ArcNet

This guide will help you set up a local ArcNet development environment and run your first inference request.

## Prerequisites

### Required Software

- **Java 11+** (for Clojure backend)
- **Clojure CLI** 1.11+
- **Node.js** 18+ and npm
- **Docker** and Docker Compose (for Kafka and XTDB)
- **Git**

### Optional Software

- **Globus Connect Personal** (for HPC integration testing)
- **Prometheus** (for metrics visualization)
- **Grafana** (for dashboards)

## Quick Start (Console Only)

The fastest way to explore ArcNet is to run the console in mock mode:

```bash
# Clone the repository
git clone https://github.com/arc-pbc-co/arcnet.git
cd arcnet

# Navigate to console
cd arcnet-console

# Install dependencies
npm install

# Start development server (mock mode)
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) in your browser. The console will display simulated telemetry data with 20 mock nodes.

### Mock Mode Features

- **Realistic Data**: Simulated nodes with varying battery levels, GPU utilization, and energy sources
- **Live Updates**: Telemetry updates every 5 seconds
- **Inference Arcs**: Animated request routing visualization
- **HPC Transfers**: Simulated dataset transfers to ORNL
- **No Backend Required**: Perfect for frontend development and demos

## Full Stack Setup

To run the complete ArcNet system with backend orchestration:

### Step 1: Start Infrastructure Services

```bash
# From repository root
cd arcnet-protocol

# Start Kafka, Zookeeper, and XTDB
docker-compose up -d

# Verify services are running
docker-compose ps
```

Expected output:
```
NAME                STATUS              PORTS
kafka               running             0.0.0.0:9092->9092/tcp
zookeeper           running             0.0.0.0:2181->2181/tcp
```

### Step 2: Configure Environment

```bash
# Copy example environment file
cp env.example .env

# Edit .env with your settings
nano .env
```

Key configuration options:

```bash
# Kafka Configuration
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# XTDB Configuration
XTDB_DATA_DIR=/tmp/arcnet-xtdb

# Geozone Configuration
GEOZONE_ID=CAISO

# Observability
PROMETHEUS_PORT=9090
OTEL_EXPORTER_ENDPOINT=http://localhost:4317
```

### Step 3: Start Regional Aggregator

```bash
# From arcnet-protocol directory
clojure -M:run aggregator

# Or with specific geozone
clojure -M:run aggregator --geozone ERCOT
```

The aggregator will:
- Connect to Kafka
- Initialize XTDB database
- Start WebSocket server on port 8080
- Expose Prometheus metrics on port 9090

### Step 4: Start Console (Live Mode)

```bash
# From arcnet-console directory
cd ../arcnet-console

# Configure WebSocket URL
echo "VITE_WS_URL=ws://localhost:8080/telemetry" > .env

# Start console
npm run dev
```

Open [http://localhost:3000](http://localhost:3000). The console will connect to the live aggregator.

### Step 5: Simulate Node Telemetry

```bash
# From arcnet-protocol directory
clojure -M:run simulator --nodes 20 --geozone CAISO

# Or run multiple geozones
clojure -M:run simulator --nodes 10 --geozone CAISO &
clojure -M:run simulator --nodes 10 --geozone ERCOT &
clojure -M:run simulator --nodes 10 --geozone PJM &
```

The simulator will:
- Generate realistic node telemetry
- Publish to `arc.telemetry.node` topic
- Simulate inference requests
- Vary battery levels and GPU utilization

## Verifying the Setup

### Check Kafka Topics

```bash
# List topics
docker exec -it kafka kafka-topics --list --bootstrap-server localhost:9092

# Expected topics:
# arc.telemetry.node
# arc.request.inference
# arc.command.dispatch.CAISO
# arc.result.inference
# arc.summary.regional
```

### Check XTDB State

```bash
# From arcnet-protocol directory
clojure -M:repl

# In REPL:
(require '[arcnet.state.regional :as regional])
(regional/start-xtdb! {:geozone-id "CAISO"})
(regional/query-all-nodes)
```

### Check Prometheus Metrics

Open [http://localhost:9090](http://localhost:9090) and query:

```promql
# Node count by geozone
arcnet_nodes_total{geozone="CAISO"}

# Average GPU utilization
arcnet_gpu_utilization_avg

# Inference request rate
rate(arcnet_inference_requests_total[5m])
```

### Check Console Connection

In the console (http://localhost:3000):

1. Look for connection status indicator in top-right corner
2. Should show "CONNECTED" in green
3. Nodes should appear on the globe within 15 seconds
4. Check browser console for `[Telemetry]` log messages

## Running Your First Inference Request

### Via Kafka Producer

```bash
# From arcnet-protocol directory
clojure -M:repl

# In REPL:
(require '[arcnet.transport.kafka :as kafka])
(require '[arcnet.schema.registry :as schema])

(def producer (kafka/create-producer
                {:bootstrap-servers "localhost:9092"
                 :client-id "test-client"}))

(kafka/send! producer
             "arc.request.inference"
             :inference-request
             {:id (str (java.util.UUID/randomUUID))
              :model-id "llama-3-70b"
              :context-window-tokens 4096
              :priority :normal
              :max-latency-ms 500
              :requester-geozone "CAISO"})
```

### Via Console CLI

In the console, open the command line (bottom panel) and type:

```bash
arcnet> status
arcnet> nodes --status=online
arcnet> select node-001 --fly
```

### Monitor Request Flow

1. **Console Globe**: Watch for cyan arc from requester to assigned node
2. **Event Log**: See "Inference dispatched" and "Inference completed" events
3. **Kafka**: Monitor `arc.command.dispatch.CAISO` and `arc.result.inference` topics
4. **Prometheus**: Query `arcnet_inference_latency_ms` histogram

## Next Steps

- **[Architecture Overview](architecture.md)** - Understand system design
- **[Console Guide](console/index.md)** - Learn console features
- **[Protocol Reference](protocol/index.md)** - Backend API documentation
- **[Development Guide](development.md)** - Contributing guidelines

## Troubleshooting

### Console shows "DISCONNECTED"

- Verify aggregator is running: `curl http://localhost:8080/health`
- Check WebSocket URL in `.env`: `VITE_WS_URL=ws://localhost:8080/telemetry`
- Check browser console for connection errors

### No nodes appearing

- Verify simulator is running and publishing telemetry
- Check Kafka topic: `docker exec -it kafka kafka-console-consumer --topic arc.telemetry.node --bootstrap-server localhost:9092`
- Check aggregator logs for XTDB write errors

### Kafka connection refused

- Verify Docker containers are running: `docker-compose ps`
- Check Kafka logs: `docker-compose logs kafka`
- Ensure port 9092 is not in use: `lsof -i :9092`

### XTDB errors

- Check data directory permissions: `ls -la /tmp/arcnet-xtdb`
- Clear XTDB data: `rm -rf /tmp/arcnet-xtdb/*`
- Restart aggregator

## Support

For issues and questions:
- Check [documentation](index.md)
- Review [architecture](architecture.md)
- Open an issue on [GitHub](https://github.com/arc-pbc-co/arcnet/issues)

