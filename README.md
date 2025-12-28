# ArcNet: Distributed AI Compute Network

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Documentation](https://img.shields.io/badge/docs-latest-brightgreen.svg)](docs/index.md)
[![Status](https://img.shields.io/badge/status-active%20development-orange.svg)]()

## Overview

ArcNet is a distributed artificial intelligence compute network designed for efficient, sustainable, and geographically-aware AI inference and training. The system orchestrates a mesh of compute nodes powered by renewable energy sources, intelligently routing requests to minimize latency and carbon footprint while seamlessly integrating with high-performance computing facilities for large-scale training workloads.

### Key Features

- **🌍 Geographic Awareness**: Route inference requests to nodes in the same or nearby geozones to minimize latency
- **⚡ Energy Optimization**: Prioritize nodes powered by co-located generation (COGEN) and renewable sources
- **🔄 Hybrid Training**: Seamlessly transition between federated training on mesh nodes and HPC facilities
- **📊 Real-Time Monitoring**: Comprehensive operations dashboard with 3D globe visualization
- **🔌 Event-Driven Architecture**: Kafka-based message streaming with strong consistency guarantees
- **📈 Comprehensive Observability**: Prometheus metrics and OpenTelemetry distributed tracing

## Architecture

```
┌─────────────────────────────────────┐
│         ORNL "Brain"                │
│   (Frontier/Lux + XTDB Tier-2)      │
└──────────────▲──────────────────────┘
               │ ornl.bridge.ingress
               │ (mTLS + Globus)
 ┌─────────────┼─────────────────────┐
 │             │                     │
 ▼             ▼                     ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Geozone   │ │Geozone   │ │Geozone   │
│West      │◄│Central   │◄│East      │
│Aggregator│ │Aggregator│ │Aggregator│
└────▲─────┘ └────▲─────┘ └────▲─────┘
     │            │            │
┌────┴────┐  ┌───┴────┐  ┌───┴────┐
│Ganglions│  │Ganglions│ │Ganglions│
│(~50 1MW │  │(~50 1MW │ │(~50 1MW │
│ nodes)  │  │ nodes)  │ │ nodes)  │
└─────────┘  └─────────┘ └─────────┘
```

## Quick Start

### Console Only (Mock Mode)

The fastest way to explore ArcNet:

```bash
git clone https://github.com/arc-pbc-co/arcnet.git
cd arcnet/arcnet-console
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) to view the operations dashboard with simulated telemetry data.

### Full Stack Setup

For complete backend orchestration:

```bash
# Start infrastructure (Kafka, XTDB)
cd arcnet-protocol
docker-compose up -d

# Start regional aggregator
clojure -M:run aggregator --geozone CAISO

# Start console (live mode)
cd ../arcnet-console
echo "VITE_WS_URL=ws://localhost:8080/telemetry" > .env
npm run dev

# Simulate nodes (in another terminal)
cd ../arcnet-protocol
clojure -M:run simulator --nodes 20 --geozone CAISO
```

See [Getting Started Guide](docs/getting-started.md) for detailed instructions.

## Components

### ArcNet Protocol (Backend)

Clojure-based orchestration system for distributed inference routing and HPC integration.

**Technologies**:
- Clojure 1.12
- Apache Kafka
- XTDB v2 (bitemporal database)
- Prometheus + OpenTelemetry

**Key Features**:
- Intelligent request routing with geographic and energy awareness
- Bitemporal state management with XTDB
- Schema-validated message passing
- Automatic HPC job classification and data movement
- Comprehensive observability

[📖 Protocol Documentation](docs/protocol/index.md)

### ArcNet Console (Frontend)

React-based real-time operations dashboard for network monitoring and management.

**Technologies**:
- React 18.3 + TypeScript 5.6
- Vite 5.4
- Zustand (state management)
- Deck.gl (3D visualization)

**Key Features**:
- Interactive 3D globe with node locations and traffic visualization
- Terminal-style command line interface
- Real-time telemetry streaming via WebSocket
- Resource monitoring and event logging
- Mock mode for development without backend

[📖 Console Documentation](docs/console/index.md)

## Documentation

### Getting Started
- **[Introduction](docs/index.md)** - System overview and use cases
- **[Getting Started](docs/getting-started.md)** - Installation and setup
- **[Architecture](docs/architecture.md)** - System design and data flow

### Component Guides
- **[ArcNet Protocol](docs/protocol/index.md)** - Backend orchestration
- **[ArcNet Console](docs/console/index.md)** - Operations dashboard
- **[API Reference](docs/api/index.md)** - WebSocket and Kafka APIs

### Operations
- **[Deployment Guide](docs/deployment.md)** - Production deployment
- **[Development Guide](docs/development.md)** - Contributing guidelines
- **[Troubleshooting](docs/troubleshooting.md)** - Common issues

## Use Cases

### 1. Edge AI Inference
Deploy AI models across geographically distributed nodes to minimize latency for edge applications such as autonomous vehicles, IoT devices, and real-time analytics.

### 2. Sustainable AI Computing
Prioritize renewable energy sources and battery-powered nodes to reduce the carbon footprint of AI workloads while maintaining performance.

### 3. Hybrid Training Workflows
Seamlessly transition between federated training on mesh nodes and large-scale training on HPC facilities based on workload characteristics.

### 4. Research Infrastructure
Provide researchers with a flexible, observable platform for experimenting with distributed AI algorithms and energy-aware scheduling.

## Technology Stack

| Component | Technology | Purpose |
|-----------|------------|---------|
| Backend Language | Clojure 1.12 | Functional, immutable, JVM-based |
| Message Broker | Apache Kafka | Event streaming and message passing |
| Database | XTDB v2 | Bitemporal, immutable state management |
| Serialization | Transit + MessagePack | Efficient data encoding |
| Frontend Framework | React 18.3 + TypeScript | Type-safe UI development |
| Build Tool | Vite 5.4 | Fast development and production builds |
| State Management | Zustand + Immer | Lightweight, immutable state |
| Visualization | Deck.gl | WebGL-powered 3D globe |
| Observability | Prometheus + OpenTelemetry | Metrics and distributed tracing |
| Data Transfer | Globus | Secure HPC data movement |

## Project Structure

```
arcnet/
├── arcnet-protocol/          # Backend orchestration (Clojure)
│   ├── src/arcnet/
│   │   ├── transport/        # Kafka integration
│   │   ├── state/            # XTDB state management
│   │   ├── scheduler/        # Inference routing
│   │   ├── bridge/           # HPC integration
│   │   └── observability/    # Metrics and tracing
│   ├── test/                 # Unit and integration tests
│   ├── resources/            # Configuration files
│   └── deps.edn              # Clojure dependencies
│
├── arcnet-console/           # Frontend dashboard (React + TypeScript)
│   ├── src/
│   │   ├── components/       # React components
│   │   ├── hooks/            # Custom hooks (WebSocket, telemetry)
│   │   ├── stores/           # Zustand state management
│   │   ├── types/            # TypeScript type definitions
│   │   └── pages/            # Page components
│   ├── docs/                 # Console-specific documentation
│   └── package.json          # Node.js dependencies
│
├── docs/                     # Comprehensive documentation
│   ├── index.md              # Documentation home
│   ├── architecture.md       # System architecture
│   ├── getting-started.md    # Setup guide
│   ├── protocol/             # Backend documentation
│   ├── console/              # Frontend documentation
│   └── api/                  # API reference
│
├── architecture.md           # High-level architecture diagram
└── README.md                 # This file
```

## Development

### Prerequisites

- Java 11+
- Clojure CLI 1.11+
- Node.js 18+
- Docker and Docker Compose

### Running Tests

```bash
# Backend tests
cd arcnet-protocol
clojure -M:test

# Frontend tests (type checking)
cd arcnet-console
npm run build
npm run lint
```

### Code Style

- **Backend**: Follow Clojure style guide, use `cljfmt` for formatting
- **Frontend**: TypeScript strict mode, ESLint + Prettier

## Contributing

We welcome contributions! Please see [Development Guide](docs/development.md) for:

- Code style guidelines
- Testing requirements
- Pull request process
- Issue reporting

## License

See [LICENSE](LICENSE) file for details.

## Acknowledgments

This project builds upon research in distributed systems, energy-aware computing, and AI infrastructure optimization. Special thanks to the teams at Oak Ridge National Laboratory for HPC integration support.

## Contact

For questions, issues, or collaboration inquiries:
- **GitHub Issues**: [https://github.com/arc-pbc-co/arcnet/issues](https://github.com/arc-pbc-co/arcnet/issues)
- **Documentation**: [docs/index.md](docs/index.md)

---

**Status**: Active Development | **Version**: 0.1.0-alpha | **Last Updated**: December 2025

