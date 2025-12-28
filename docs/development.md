# ArcNet Development Guide

## Getting Started

### Prerequisites

- **Java 11+** (OpenJDK recommended)
- **Clojure CLI** 1.11+
- **Node.js** 18+ and npm
- **Docker** and Docker Compose
- **Git**

### Clone Repository

```bash
git clone https://github.com/arc-pbc-co/arcnet.git
cd arcnet
```

### Project Structure

```
arcnet/
├── arcnet-protocol/          # Backend (Clojure)
│   ├── src/arcnet/
│   │   ├── transport/        # Kafka integration
│   │   ├── state/            # XTDB state management
│   │   ├── scheduler/        # Inference routing
│   │   ├── bridge/           # HPC integration
│   │   ├── observability/    # Metrics and tracing
│   │   └── schema/           # Message schemas
│   ├── test/                 # Tests
│   ├── resources/            # Configuration
│   └── deps.edn              # Dependencies
│
├── arcnet-console/           # Frontend (React + TypeScript)
│   ├── src/
│   │   ├── components/       # React components
│   │   ├── hooks/            # Custom hooks
│   │   ├── stores/           # Zustand state
│   │   ├── types/            # TypeScript types
│   │   └── pages/            # Page components
│   ├── public/               # Static assets
│   └── package.json          # Dependencies
│
└── docs/                     # Documentation
```

## Backend Development (Clojure)

### Setup

```bash
cd arcnet-protocol

# Install dependencies
clojure -P

# Start infrastructure
docker-compose up -d

# Verify Kafka is running
docker-compose ps
```

### REPL-Driven Development

```bash
# Start REPL
clojure -M:repl

# Or with nREPL server
clojure -M:nrepl
```

**In REPL**:

```clojure
;; Load namespace
(require '[arcnet.state.regional :as regional])
(require '[arcnet.transport.kafka :as kafka])

;; Start XTDB
(def xtdb-node (regional/start-xtdb! {:geozone-id "CAISO"}))

;; Query nodes
(regional/query-all-nodes)

;; Create Kafka producer
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

;; Reload namespace after changes
(require '[arcnet.state.regional :as regional] :reload)
```

### Running Components

```bash
# Regional aggregator
clojure -M:run aggregator --geozone CAISO

# Scheduler
clojure -M:run scheduler --geozone CAISO

# Bridge orchestrator
clojure -M:run bridge

# Simulator
clojure -M:run simulator --nodes 20 --geozone CAISO
```

### Testing

```bash
# Run all tests
clojure -M:test

# Run specific namespace
clojure -M:test -n arcnet.scheduler.core-test

# Run with coverage
clojure -M:test:coverage

# Watch mode (auto-run on file change)
clojure -M:test:watch
```

### Code Style

**Formatting**:
```bash
# Format code
clojure -M:cljfmt fix

# Check formatting
clojure -M:cljfmt check
```

**Linting**:
```bash
# Run clj-kondo
clojure -M:clj-kondo --lint src test
```

**Conventions**:
- Use kebab-case for function and variable names
- Use namespaced keywords for domain entities (`:node/id`, `:request/priority`)
- Prefer pure functions over stateful operations
- Document public functions with docstrings
- Use `let` for intermediate values
- Avoid deeply nested code (max 3 levels)

### Adding New Features

1. **Create namespace** in `src/arcnet/`
2. **Write tests** in `test/arcnet/`
3. **Implement feature** using REPL-driven development
4. **Add schema** if introducing new message types
5. **Update documentation**
6. **Run tests** and ensure they pass
7. **Submit pull request**

**Example: Adding new message type**

```clojure
;; 1. Define schema in src/arcnet/schema/registry.clj
(def NodeAlert
  [:map
   [:schema/version [:= 1]]
   [:node-id :uuid]
   [:alert-type [:enum :low-battery :high-utilization :offline]]
   [:severity [:enum :info :warn :error]]
   [:message :string]
   [:timestamp :inst]])

;; 2. Register schema
(def schemas
  {:node-telemetry NodeTelemetry
   :inference-request InferenceRequest
   :node-alert NodeAlert}) ; Add here

;; 3. Create producer/consumer
(kafka/send! producer
             "arc.alert.node"
             :node-alert
             {:node-id node-id
              :alert-type :low-battery
              :severity :warn
              :message "Battery level below 20%"
              :timestamp (java.time.Instant/now)})

;; 4. Write tests
(deftest test-node-alert-schema
  (is (m/validate NodeAlert
        {:schema/version 1
         :node-id (java.util.UUID/randomUUID)
         :alert-type :low-battery
         :severity :warn
         :message "Battery level below 20%"
         :timestamp (java.time.Instant/now)})))
```

## Frontend Development (React + TypeScript)

### Setup

```bash
cd arcnet-console

# Install dependencies
npm install

# Start development server (mock mode)
npm run dev

# Or with live backend
echo "VITE_WS_URL=ws://localhost:8080/telemetry" > .env
npm run dev
```

### Development Server

```bash
# Start with mock data
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview

# Type checking
npm run build

# Linting
npm run lint
```

### Code Style

**TypeScript**:
- Use strict mode
- Prefer interfaces over types for object shapes
- Use enums for fixed sets of values
- Avoid `any` type
- Document complex types with JSDoc comments

**React**:
- Use functional components with hooks
- Prefer composition over inheritance
- Use `React.memo` for expensive components
- Extract custom hooks for reusable logic
- Keep components small (< 200 lines)

**Formatting**:
```bash
# Format code (if Prettier is configured)
npm run format

# Check formatting
npm run format:check
```

### Adding New Features

1. **Create component** in `src/components/`
2. **Define types** in `src/types/`
3. **Add state** to Zustand store if needed
4. **Implement component** with TypeScript
5. **Test manually** in browser
6. **Update documentation**
7. **Submit pull request**

**Example: Adding new visualization**

```typescript
// 1. Define types in src/types/index.ts
export interface NetworkLink {
  id: string;
  sourceNodeId: string;
  targetNodeId: string;
  bandwidth: number;
  latency: number;
  timestamp: string;
}

// 2. Add to store in src/stores/arcnetStore.ts
interface ArcnetState {
  // ... existing state
  networkLinks: NetworkLink[];
  addNetworkLink: (link: NetworkLink) => void;
}

export const useArcnetStore = create<ArcnetState>()(
  immer((set) => ({
    // ... existing state
    networkLinks: [],
    addNetworkLink: (link) =>
      set((state) => {
        state.networkLinks.push(link);
      }),
  }))
);

// 3. Create component in src/components/NetworkLinksLayer.tsx
import { ArcLayer } from '@deck.gl/layers';
import { useArcnetStore } from '../stores/arcnetStore';

export function NetworkLinksLayer() {
  const networkLinks = useArcnetStore((state) => state.networkLinks);
  const nodes = useArcnetStore((state) => state.nodes);

  const arcs = networkLinks.map((link) => {
    const source = nodes.find((n) => n.id === link.sourceNodeId);
    const target = nodes.find((n) => n.id === link.targetNodeId);
    
    if (!source || !target) return null;
    
    return {
      sourcePosition: [source.lng, source.lat],
      targetPosition: [target.lng, target.lat],
      color: [255, 255, 0], // Yellow
      width: link.bandwidth / 1000,
    };
  }).filter(Boolean);

  return new ArcLayer({
    id: 'network-links',
    data: arcs,
    getSourcePosition: (d) => d.sourcePosition,
    getTargetPosition: (d) => d.targetPosition,
    getSourceColor: (d) => d.color,
    getTargetColor: (d) => d.color,
    getWidth: (d) => d.width,
  });
}

// 4. Use in GlobeView.tsx
import { NetworkLinksLayer } from './NetworkLinksLayer';

// In layers array:
const layers = [
  // ... existing layers
  NetworkLinksLayer(),
];
```

## Testing

### Backend Tests

```bash
cd arcnet-protocol

# Run all tests
clojure -M:test

# Run specific test
clojure -M:test -n arcnet.scheduler.core-test

# Run with coverage
clojure -M:test:coverage
```

### Frontend Tests

```bash
cd arcnet-console

# Type checking (acts as test)
npm run build

# Linting
npm run lint

# Manual testing checklist
# See ../TESTING_CHECKLIST.md
```

## Git Workflow

### Branch Naming

- `feature/description` - New features
- `fix/description` - Bug fixes
- `docs/description` - Documentation updates
- `refactor/description` - Code refactoring

### Commit Messages

Follow conventional commits:

```
feat: add network links visualization
fix: resolve WebSocket reconnection issue
docs: update deployment guide
refactor: simplify scheduler scoring logic
test: add tests for XTDB queries
```

### Pull Request Process

1. **Create branch** from `main`
2. **Make changes** with clear commits
3. **Run tests** and ensure they pass
4. **Update documentation** if needed
5. **Push branch** to GitHub
6. **Create pull request** with description
7. **Address review comments**
8. **Merge** after approval

## Debugging

### Backend Debugging

```clojure
;; Add logging
(require '[clojure.tools.logging :as log])

(log/info "Processing request" {:request-id request-id})
(log/warn "Low battery detected" {:node-id node-id :level battery-level})
(log/error "Failed to dispatch" {:error error})

;; Use tap>
(tap> {:event :node-telemetry :data telemetry})

;; In REPL:
(add-tap #'clojure.pprint/pprint)
```

### Frontend Debugging

```typescript
// Console logging
console.log('[Telemetry]', message);
console.warn('[WebSocket] Connection lost');
console.error('[Store] Failed to update node', error);

// React DevTools
// Install: https://react.dev/learn/react-developer-tools

// Zustand DevTools
// Already configured in store
```

## Next Steps

- **[Architecture](architecture.md)** - Understand system design
- **[API Reference](api/index.md)** - Integration APIs
- **[Troubleshooting](troubleshooting.md)** - Common issues

