# Changelog

All notable changes to ArcNet will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial project structure and architecture
- ArcNet Protocol backend orchestration system
- ArcNet Console real-time operations dashboard
- Comprehensive documentation suite

## [0.1.0-alpha] - 2025-12-28

### Added

#### Backend (ArcNet Protocol)
- **Transport Layer**
  - Kafka producer/consumer wrappers with schema validation
  - Transit + MessagePack serialization
  - Malli schema registry for message validation
  - Dead-letter queue for invalid messages
  - Prometheus metrics for Kafka operations

- **State Management**
  - XTDB v2 integration for bitemporal state
  - Regional aggregator state management
  - Datalog query patterns for node selection
  - Automatic state synchronization from Kafka

- **Scheduler**
  - Geographic-aware inference routing
  - Energy-optimized node selection (COGEN priority)
  - GPU utilization-based load balancing
  - Optimistic locking for node reservations
  - Priority queue support (critical, normal, background)

- **Bridge Orchestrator**
  - Training job classification (HPC vs. federated)
  - Globus-based data transfer to ORNL
  - Transfer progress monitoring
  - Automatic retry on failure

- **Observability**
  - Prometheus metrics endpoint
  - OpenTelemetry distributed tracing
  - Structured logging with context
  - Health check endpoints

- **Message Schemas**
  - NodeTelemetry v2
  - InferenceRequest v1
  - InferenceDispatch v1
  - InferenceResult v1
  - TrainingJob v1

#### Frontend (ArcNet Console)
- **3D Globe Visualization**
  - WebGL-powered interactive globe using Deck.gl
  - Node markers with status color coding
  - Animated inference arcs (cyan)
  - HPC transfer arcs (purple)
  - Camera presets (global, North America, Europe, Asia, ORNL)
  - Click-to-select node interaction

- **Command Line Interface**
  - Terminal-style CLI with syntax highlighting
  - Command history with ↑/↓ navigation
  - Tab autocomplete
  - Commands: status, nodes, select, fly, events, jobs, stats, history, clear, help
  - Filter support for nodes (status, energy, geozone)
  - Real-time command suggestions

- **Resource Monitoring**
  - Header statistics (nodes, COGEN %, GPU util, RPS, latency, HPC jobs)
  - Node detail panel with real-time metrics
  - Energy source breakdown
  - Model availability tracking

- **Event Logging**
  - Real-time event stream
  - Color-coded severity levels
  - Auto-scroll to latest events
  - Event filtering and search

- **Telemetry Integration**
  - WebSocket connection with auto-reconnect
  - Exponential backoff retry strategy
  - Mock mode for development
  - Connection status indicator

- **State Management**
  - Zustand store with Immer for immutability
  - Optimized re-renders with selectors
  - Persistent view state

#### Documentation
- **Core Documentation**
  - README.md with project overview
  - Architecture documentation with system design
  - Getting started guide with quick start
  - Deployment guide for production
  - Development guide for contributors
  - Troubleshooting guide with common issues
  - Contributing guidelines

- **Component Documentation**
  - Protocol documentation (backend)
  - Console documentation (frontend)
  - CLI guide with command reference
  - API reference (WebSocket and Kafka)

- **Supporting Documentation**
  - Documentation summary and navigation
  - Changelog (this file)

#### Infrastructure
- **Docker Compose**
  - Kafka broker configuration
  - Zookeeper configuration
  - Volume management for persistence

- **Configuration**
  - Environment variable support
  - config.edn for Clojure components
  - .env for console configuration

### Changed
- N/A (initial release)

### Deprecated
- N/A (initial release)

### Removed
- N/A (initial release)

### Fixed
- N/A (initial release)

### Security
- mTLS support for HPC integration
- Schema validation for all messages
- CORS configuration for WebSocket

## Release Notes

### [0.1.0-alpha] - Initial Alpha Release

This is the initial alpha release of ArcNet, providing core functionality for distributed AI inference routing and HPC integration.

**Highlights**:
- ✅ Functional backend orchestration with Kafka and XTDB
- ✅ Real-time operations dashboard with 3D visualization
- ✅ Geographic and energy-aware inference routing
- ✅ HPC integration with Globus data transfer
- ✅ Comprehensive documentation

**Known Limitations**:
- Mock telemetry data (no real compute nodes yet)
- Limited HPC integration testing
- No authentication/authorization
- Single-region deployment only
- No REST API (WebSocket and Kafka only)

**Next Steps**:
- Add authentication and authorization
- Implement REST API
- Add federated training coordination
- Expand test coverage
- Performance optimization
- Multi-region deployment support

## Versioning

ArcNet follows [Semantic Versioning](https://semver.org/):

- **MAJOR** version for incompatible API changes
- **MINOR** version for backwards-compatible functionality additions
- **PATCH** version for backwards-compatible bug fixes
- **Pre-release** tags (alpha, beta, rc) for development versions

## Links

- [Repository](https://github.com/arc-pbc-co/arcnet)
- [Documentation](docs/index.md)
- [Issues](https://github.com/arc-pbc-co/arcnet/issues)
- [Contributing](CONTRIBUTING.md)

