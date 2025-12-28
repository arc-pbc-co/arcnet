# ArcNet: Distributed AI Compute Network

## Introduction

ArcNet is a distributed artificial intelligence compute network designed to enable efficient, sustainable, and geographically-aware AI inference and training across a mesh of compute nodes. The system addresses the growing need for distributed AI infrastructure that can:

- **Optimize for Energy Efficiency**: Prioritize renewable energy sources (co-located generation) and battery-powered nodes
- **Minimize Latency**: Route inference requests to geographically proximate nodes
- **Scale Dynamically**: Adapt to changing network conditions and resource availability
- **Integrate with HPC**: Seamlessly transfer large-scale training workloads to supercomputing facilities
- **Provide Real-Time Visibility**: Monitor network health, resource utilization, and inference traffic

## System Overview

ArcNet consists of three primary components:

1. **ArcNet Protocol** - Clojure-based backend orchestration system
2. **ArcNet Console** - React-based real-time operations dashboard
3. **Regional Aggregators** - Distributed state management with XTDB and Kafka

### Architecture Vision

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

## Key Features

### Intelligent Request Routing
- **Geographic Awareness**: Route requests to nodes in the same or nearby geozones
- **Energy Optimization**: Prefer nodes powered by co-located generation (COGEN)
- **Load Balancing**: Distribute requests based on GPU utilization and availability
- **Priority Queuing**: Support critical, normal, and background priority levels

### Real-Time Telemetry
- **Node Monitoring**: Track battery levels, GPU utilization, energy sources, and model availability
- **Inference Tracking**: Visualize request routing and completion across the network
- **HPC Integration**: Monitor dataset transfers to supercomputing facilities
- **Event Streaming**: Real-time alerts for node status changes, low battery, and system events

### Hybrid Training Architecture
- **Federated Training**: Distribute training across mesh nodes for smaller workloads
- **HPC Offloading**: Automatically route large-scale training jobs to ORNL Frontier
- **Data Movement**: Globus-based secure data transfer to HPC facilities
- **Checkpoint Management**: Seamless checkpoint synchronization between mesh and HPC

### Operations Dashboard
- **3D Globe Visualization**: Interactive globe showing node locations and network traffic
- **Command Line Interface**: Terminal-style CLI for network operations
- **Resource Monitoring**: Real-time GPU, battery, and energy metrics
- **Event Logging**: Comprehensive event stream with filtering and search

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

### Backend (ArcNet Protocol)
- **Language**: Clojure 1.12
- **Message Broker**: Apache Kafka
- **Database**: XTDB v2 (bitemporal, immutable)
- **Serialization**: Transit + MessagePack
- **Observability**: Prometheus metrics, OpenTelemetry tracing
- **Data Transfer**: Globus for HPC integration

### Frontend (ArcNet Console)
- **Framework**: React 18.3 with TypeScript 5.6
- **Build Tool**: Vite 5.4
- **State Management**: Zustand with Immer
- **Visualization**: Deck.gl (WebGL-powered 3D globe)
- **Styling**: CSS Modules
- **Real-Time**: WebSocket with auto-reconnect

## Documentation Structure

- **[Architecture Overview](architecture.md)** - System design and component interactions
- **[Getting Started](getting-started.md)** - Installation and quick start guide
- **[ArcNet Protocol](protocol/index.md)** - Backend orchestration system
- **[ArcNet Console](console/index.md)** - Operations dashboard
- **[API Reference](api/index.md)** - WebSocket protocol and REST APIs
- **[Deployment Guide](deployment.md)** - Production deployment instructions
- **[Development Guide](development.md)** - Contributing and development workflow

## Project Status

ArcNet is currently in active development. The core infrastructure for distributed inference routing, telemetry streaming, and HPC integration is operational. The operations console provides real-time visibility into network state and performance.

## License

See LICENSE file in repository root.

## Acknowledgments

This project builds upon research in distributed systems, energy-aware computing, and AI infrastructure optimization.

