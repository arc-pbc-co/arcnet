# ArcNet Console

## Overview

The ArcNet Console is a real-time operations dashboard for monitoring and managing the distributed AI compute network. Built with React and TypeScript, it provides an intuitive interface for visualizing network topology, tracking inference requests, monitoring resource utilization, and managing nodes.

## Key Features

### 🌍 Interactive 3D Globe

The centerpiece of the console is a WebGL-powered 3D globe visualization that displays:

- **Node Locations**: Compute nodes positioned by geographic coordinates
- **Status Indicators**: Color-coded markers showing node operational status
  - 🟢 Green: Online and available
  - 🟡 Yellow: Busy processing requests
  - 🟠 Orange: Stale (no recent telemetry)
  - 🔴 Red: Offline
- **Inference Arcs**: Animated cyan arcs showing request routing from source to target node
- **HPC Transfers**: Purple arcs indicating dataset transfers to ORNL Frontier
- **Hub Markers**: Special markers for central hubs (e.g., ORNL)
- **Energy Source Indicators**: Visual distinction between COGEN, grid, and battery-powered nodes

**Interaction**:
- Click nodes to view detailed information
- Drag to rotate the globe
- Scroll to zoom in/out
- Use camera presets (Global, North America, Europe, Asia, ORNL)

### ⌨️ Command Line Interface

A terminal-style CLI provides powerful network operations:

**Features**:
- Command history with ↑/↓ navigation
- Tab autocomplete
- Syntax highlighting
- Real-time command suggestions
- Direct integration with network state

**Available Commands**:

```bash
# System Status
status                          # Display global network statistics
help                            # Show all available commands

# Node Management
nodes                           # List all nodes
nodes --status=online           # Filter by status
nodes --energy=cogen            # Filter by energy source
nodes --geozone=CAISO           # Filter by geozone
select <node-id>                # Select a node
select <node-id> --fly          # Select and fly camera to node

# Network Operations
route <from> <to>               # Calculate route between nodes
fly <preset>                    # Fly to camera preset (global, northAmerica, etc.)
jobs                            # List HPC jobs
jobs --status=running           # Filter jobs by status

# Event Management
events                          # Show recent events
events --limit=50               # Show more events
history                         # Show event history

# Statistics
stats                           # Show global statistics
stats --geozone=ERCOT           # Show geozone-specific statistics

# Utility
clear                           # Clear command output
```

See [CLI Guide](cli-guide.md) for detailed command reference.

### 📊 Resource Monitoring

**Header Statistics**:
- **Nodes**: Online count / Total count
- **COGEN**: Percentage of nodes powered by co-located generation
- **GPU Utilization**: Network-wide average
- **Inference RPS**: Requests per second
- **P99 Latency**: 99th percentile inference latency
- **HPC Jobs**: Pending and active counts

**Resource Panel** (right sidebar):
- GPU utilization histogram
- Battery level distribution
- Energy source breakdown
- Model availability matrix
- Geozone statistics

**Node Detail Panel**:
- Selected node information
- Real-time metrics
- Loaded models
- Recent activity
- Reservation status

### 📝 Event Log

Real-time event stream showing:
- Node status changes (online, offline, stale)
- Inference events (dispatched, completed, failed)
- HPC transfer events (initiated, completed)
- System alerts (low battery, high utilization)
- Network events (geozone alerts)

**Features**:
- Auto-scroll to latest events
- Color-coded severity levels
- Timestamp display
- Event filtering
- Search functionality

### 🔌 Connection Management

**Connection Status Indicator** (top-right corner):
- **Green (CONNECTED)**: Live WebSocket connection to aggregator
- **Yellow (CONNECTING)**: Attempting to connect
- **Red (DISCONNECTED)**: No connection
- **Blue (MOCK)**: Using simulated data

**Auto-Reconnect**:
- Exponential backoff (2s, 4s, 8s, ..., max 30s)
- Automatic retry on connection loss
- Manual reconnect button
- Connection state persistence

## Architecture

### Component Hierarchy

```
App
├── ConsoleLayout
│   ├── ConsoleHeader
│   │   ├── ConnectionStatus
│   │   └── Clock
│   ├── GlobeView
│   │   ├── DeckGL
│   │   ├── ScatterplotLayer (nodes)
│   │   ├── ArcLayer (inference)
│   │   └── ArcLayer (HPC)
│   ├── Sidebar
│   │   ├── ResourcePanel
│   │   └── NodeDetail
│   ├── EventLog
│   └── CommandLine
└── KeyboardShortcutsHelp
```

### State Management

**Zustand Store** (`arcnetStore.ts`):

```typescript
interface ArcnetState {
  // Node state
  nodes: Node[];
  selectedNodeId: string | null;
  
  // Traffic visualization
  inferenceArcs: InferenceArc[];
  hpcTransfers: HpcTransfer[];
  
  // Event log
  events: ConsoleEvent[];
  
  // Global statistics
  globalStats: GlobalStats | null;
  geozoneStats: Map<string, GeozoneStats>;
  
  // View state (camera)
  viewState: ViewState;
  
  // Connection state
  isConnected: boolean;
}
```

**Actions**:
- `addNode(node)` / `updateNode(id, updates)` / `removeNode(id)`
- `addInferenceArc(arc)` / `removeInferenceArc(id)`
- `addHpcTransfer(transfer)` / `updateHpcTransfer(id, updates)`
- `addEvent(event)`
- `setViewState(viewState)` / `flyTo(preset)`
- `selectNode(id)` / `deselectNode()`

### Telemetry Integration

**Live Mode** (`useArcnetTelemetry`):
- Connects to WebSocket endpoint
- Parses protocol messages
- Updates Zustand store
- Handles reconnection

**Mock Mode** (`useMockTelemetry`):
- Generates realistic simulated data
- 20 nodes with random positions
- Updates every 5 seconds
- Simulates inference arcs and HPC transfers

See [Telemetry Integration](telemetry.md) for details.

## Configuration

### Environment Variables

Create `.env` file in `arcnet-console/`:

```bash
# WebSocket URL (leave empty for mock mode)
VITE_WS_URL=ws://localhost:8080/telemetry

# Enable debug logging
VITE_DEBUG_TELEMETRY=true

# Custom configuration
VITE_UPDATE_INTERVAL=5000
VITE_MAX_EVENTS=1000
```

### Customization

**Theme Colors** (`index.css`):
```css
:root {
  --terminal-green: #00ff41;
  --status-online: #00ff41;
  --status-busy: #ffaa00;
  --status-cogen: #00d4ff;
  --bg-dark: #0a0e14;
}
```

**Globe Settings** (`GlobeView.tsx`):
```typescript
const GLOBE_CONFIG = {
  ambientLight: 0.3,
  pointLight: 0.7,
  nodeRadius: 50000,
  arcWidth: 2,
  animationSpeed: 0.01,
};
```

## Development

### Running Locally

```bash
cd arcnet-console
npm install
npm run dev
```

### Building for Production

```bash
npm run build
npm run preview
```

### Testing

```bash
# Type checking
npm run build

# Linting
npm run lint

# Manual testing
# See ../TESTING_CHECKLIST.md
```

## Deployment

### Static Hosting

The console is a static single-page application that can be deployed to any static hosting service:

- **Netlify**: `netlify deploy --prod --dir=dist`
- **Vercel**: `vercel --prod`
- **AWS S3**: `aws s3 sync dist/ s3://bucket-name`
- **GitHub Pages**: Configure in repository settings

### Docker

```dockerfile
FROM nginx:alpine
COPY dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

```bash
docker build -t arcnet-console .
docker run -p 8080:80 arcnet-console
```

### Environment Variables at Runtime

For dynamic WebSocket URL configuration:

```bash
# Build with placeholder
VITE_WS_URL=__WS_URL__ npm run build

# Replace at runtime
sed -i 's|__WS_URL__|wss://telemetry.arcnet.io|g' dist/index.html
```

## Browser Compatibility

- **Chrome** 90+
- **Firefox** 88+
- **Safari** 14+
- **Edge** 90+

**Requirements**:
- WebGL 2.0 support
- WebSocket support
- ES2020 JavaScript features

## Performance

### Optimization Techniques

- **Memoization**: React.memo and useMemo for expensive computations
- **Virtualization**: Event log uses virtual scrolling for large lists
- **Debouncing**: Globe interactions debounced to reduce re-renders
- **Web Workers**: Considered for heavy data processing (future)

### Performance Targets

- **Initial Load**: < 2s on 3G connection
- **Frame Rate**: 60 FPS during globe interaction
- **Memory**: < 200MB for 1000 nodes
- **Telemetry Lag**: < 100ms from WebSocket message to UI update

## Accessibility

- **Keyboard Navigation**: Full keyboard support for CLI
- **Screen Readers**: ARIA labels on interactive elements
- **High Contrast**: Terminal-style theme with high contrast
- **Focus Management**: Clear focus indicators

## Next Steps

- **[CLI Guide](cli-guide.md)** - Detailed command reference
- **[Telemetry Integration](telemetry.md)** - WebSocket protocol
- **[Component Reference](components.md)** - React component documentation
- **[Customization Guide](customization.md)** - Theming and configuration

