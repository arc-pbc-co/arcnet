# ArcNet Quick Reference

## 🚀 Quick Start

### Console Only (Mock Mode)
```bash
cd arcnet-console
npm install
npm run dev
# Open http://localhost:3000
```

### Full Stack
```bash
# Terminal 1: Infrastructure
cd arcnet-protocol
docker-compose up -d

# Terminal 2: Aggregator
clojure -M:run aggregator --geozone CAISO

# Terminal 3: Console
cd arcnet-console
echo "VITE_WS_URL=ws://localhost:8080/telemetry" > .env
npm run dev

# Terminal 4: Simulator
cd arcnet-protocol
clojure -M:run simulator --nodes 20 --geozone CAISO
```

## 📋 CLI Commands

| Command | Description | Example |
|---------|-------------|---------|
| `status` | Show network statistics | `status` |
| `nodes` | List nodes | `nodes --status=online --energy=cogen` |
| `select` | Select node | `select node-001 --fly` |
| `fly` | Move camera | `fly northAmerica` |
| `events` | Show events | `events --limit=50` |
| `jobs` | List HPC jobs | `jobs --status=running` |
| `stats` | Show statistics | `stats --geozone=CAISO` |
| `history` | Command history | `history` |
| `clear` | Clear output | `clear` |
| `help` | Show help | `help` |

## 🔧 Configuration

### Backend (.env or config.edn)
```bash
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
XTDB_DATA_DIR=/tmp/arcnet-xtdb
GEOZONE_ID=CAISO
PROMETHEUS_PORT=9090
LOG_LEVEL=info
```

### Frontend (.env)
```bash
VITE_WS_URL=ws://localhost:8080/telemetry
VITE_DEBUG_TELEMETRY=true
```

## 📊 Kafka Topics

| Topic | Purpose | Retention |
|-------|---------|-----------|
| `arc.telemetry.node` | Node telemetry | 7 days |
| `arc.request.inference` | Inference requests | 24 hours |
| `arc.command.dispatch.{geozone}` | Dispatch commands | 1 hour |
| `arc.result.inference` | Inference results | 7 days |
| `arc.job.submission` | Training jobs | 30 days |
| `arc.bridge.pending` | HPC pending | 30 days |
| `ornl.bridge.ingress` | HPC ready | 90 days |

## 🔌 WebSocket Messages

### Telemetry Update
```json
{
  "type": "telemetry",
  "node": {
    "id": "node-001",
    "geohash": "9q9hvu",
    "energySource": "cogen",
    "batteryLevel": 0.87,
    "gpuUtilization": 0.42,
    "modelsLoaded": ["llama-3-70b"]
  }
}
```

### Inference Event
```json
{
  "type": "inference",
  "requestId": "req-123",
  "source": [-122.4194, 37.7749],
  "targetNodeId": "node-001",
  "modelId": "llama-3-70b",
  "status": "completed",
  "latencyMs": 245
}
```

## 🐛 Troubleshooting

### Console shows "DISCONNECTED"
```bash
# Check aggregator
curl http://localhost:8080/health

# Check WebSocket URL
cat arcnet-console/.env

# Restart aggregator
clojure -M:run aggregator --geozone CAISO
```

### No nodes appearing
```bash
# Check Kafka messages
docker exec -it kafka kafka-console-consumer \
  --topic arc.telemetry.node \
  --bootstrap-server localhost:9092

# Start simulator
clojure -M:run simulator --nodes 20 --geozone CAISO
```

### Kafka connection refused
```bash
# Check Kafka status
docker-compose ps

# Restart Kafka
docker-compose restart kafka

# Check port
lsof -i :9092
```

## 📈 Prometheus Metrics

| Metric | Description |
|--------|-------------|
| `arcnet_nodes_total` | Node count by status |
| `arcnet_gpu_utilization_avg` | Average GPU utilization |
| `arcnet_inference_requests_total` | Request count |
| `arcnet_inference_latency_ms` | Latency histogram |
| `arcnet_kafka_produce_duration_ms` | Kafka produce latency |

**Query Examples**:
```promql
# Node count
arcnet_nodes_total{geozone="CAISO"}

# P99 latency
histogram_quantile(0.99, rate(arcnet_inference_latency_ms_bucket[5m]))

# Request rate
rate(arcnet_inference_requests_total[5m])
```

## 🔑 Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `` ` `` | Focus CLI |
| `↑` / `↓` | Command history |
| `Tab` | Autocomplete |
| `Ctrl+C` | Cancel command |
| `Ctrl+L` | Clear output |
| `Esc` | Unfocus CLI |

## 🏗️ Architecture Layers

```
┌─────────────────────────────────────┐
│         ORNL "Brain"                │  Global coordination
│   (Frontier/Lux + XTDB Tier-2)      │  HPC integration
└──────────────▲──────────────────────┘
               │
 ┌─────────────┼─────────────────────┐
 │             │                     │
 ▼             ▼                     ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Regional  │ │Regional  │ │Regional  │  Regional aggregation
│Aggregator│ │Aggregator│ │Aggregator│  State management
└────▲─────┘ └────▲─────┘ └────▲─────┘
     │            │            │
┌────┴────┐  ┌───┴────┐  ┌───┴────┐
│Compute  │  │Compute │  │Compute │   Inference execution
│Nodes    │  │Nodes   │  │Nodes   │   Telemetry publishing
└─────────┘  └────────┘  └─────────┘
```

## 📚 Documentation Links

| Topic | Link |
|-------|------|
| **Getting Started** | [docs/getting-started.md](getting-started.md) |
| **Architecture** | [docs/architecture.md](architecture.md) |
| **Protocol** | [docs/protocol/index.md](protocol/index.md) |
| **Console** | [docs/console/index.md](console/index.md) |
| **CLI Guide** | [docs/console/cli-guide.md](console/cli-guide.md) |
| **API Reference** | [docs/api/index.md](api/index.md) |
| **Deployment** | [docs/deployment.md](deployment.md) |
| **Development** | [docs/development.md](development.md) |
| **Troubleshooting** | [docs/troubleshooting.md](troubleshooting.md) |
| **Contributing** | [CONTRIBUTING.md](../CONTRIBUTING.md) |

## 🔗 Useful Commands

### Backend Development
```bash
# Start REPL
clojure -M:repl

# Run tests
clojure -M:test

# Format code
clojure -M:cljfmt fix

# Run component
clojure -M:run aggregator --geozone CAISO
```

### Frontend Development
```bash
# Install dependencies
npm install

# Start dev server
npm run dev

# Build for production
npm run build

# Type checking
npm run build

# Linting
npm run lint
```

### Docker Commands
```bash
# Start infrastructure
docker-compose up -d

# Stop infrastructure
docker-compose down

# View logs
docker-compose logs -f kafka

# Check status
docker-compose ps
```

### Kafka Commands
```bash
# List topics
docker exec -it kafka kafka-topics --list --bootstrap-server localhost:9092

# Consume messages
docker exec -it kafka kafka-console-consumer \
  --topic arc.telemetry.node \
  --bootstrap-server localhost:9092

# Consumer groups
docker exec -it kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group arcnet-aggregator-caiso
```

## 🎯 Common Tasks

| Task | Command |
|------|---------|
| **Start console (mock)** | `cd arcnet-console && npm run dev` |
| **Start backend** | `cd arcnet-protocol && clojure -M:run aggregator` |
| **Simulate nodes** | `clojure -M:run simulator --nodes 20` |
| **Check Kafka** | `docker exec -it kafka kafka-topics --list --bootstrap-server localhost:9092` |
| **View logs** | `docker-compose logs -f` |
| **Run tests** | `clojure -M:test` |
| **Build console** | `cd arcnet-console && npm run build` |

## 📞 Getting Help

- **Documentation**: [docs/index.md](index.md)
- **GitHub Issues**: https://github.com/arc-pbc-co/arcnet/issues
- **Troubleshooting**: [docs/troubleshooting.md](troubleshooting.md)

---

**Version**: 0.1.0-alpha | **Last Updated**: December 2025

