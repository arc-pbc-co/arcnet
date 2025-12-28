# ArcNet Console

Terminal-styled operations dashboard for distributed AI network monitoring.

![ArcNet Console](https://img.shields.io/badge/status-active-brightgreen) ![React](https://img.shields.io/badge/react-18.3-blue) ![TypeScript](https://img.shields.io/badge/typescript-5.6-blue) ![Vite](https://img.shields.io/badge/vite-5.4-purple)

## Overview

ArcNet Console is a real-time operations dashboard for monitoring a distributed AI compute network. It provides:

- **3D Globe Visualization** - Interactive globe showing node locations and network traffic
- **Real-Time Telemetry** - Live updates via WebSocket or simulated data
- **Resource Monitoring** - GPU utilization, battery levels, energy sources
- **Inference Tracking** - Visualize AI inference requests across the network
- **HPC Integration** - Monitor dataset transfers to supercomputing facilities
- **Command Line Interface** - Terminal-style CLI for network operations
- **Event Logging** - Real-time event stream with filtering

## Quick Start

### Installation

```bash
cd arcnet-console
npm install
```

### Development (Mock Mode)

```bash
npm run dev
```

Open [http://localhost:3000](http://localhost:3000)

The console starts in **mock mode** by default, generating realistic simulated telemetry data.

### Development (Live Mode)

1. Configure WebSocket URL:
```bash
echo "VITE_WS_URL=ws://localhost:8080/telemetry" > .env
```

2. Start the console:
```bash
npm run dev
```

### Production Build

```bash
npm run build
npm run preview
```

## Features

### 🌍 Interactive Globe

- **Deck.gl** powered 3D visualization
- Real-time node positions with status indicators
- Animated inference arcs showing request routing
- HPC transfer visualization to ORNL Frontier
- Camera presets (Global, North America, Europe, Asia, ORNL)
- Click nodes for detailed information

### 📊 Real-Time Telemetry

- **WebSocket Integration** - Connect to live telemetry stream
- **Mock Mode** - Simulated data for development
- **Auto-Reconnect** - Exponential backoff on connection loss
- **Connection Status** - Visual indicator in header
- **Protocol Support** - Compatible with arcnet-protocol schema

See [TELEMETRY_INTEGRATION.md](docs/TELEMETRY_INTEGRATION.md) for details.

### ⌨️ Command Line Interface

- **Dual Modes** - Integrated CLI and full-screen demo
- **Command History** - Arrow key navigation
- **Auto-Complete** - Tab completion for commands
- **Help System** - Built-in documentation
- **Syntax Highlighting** - Color-coded output

See [CLI_GUIDE.md](docs/CLI_GUIDE.md) for command reference.

### 📈 Resource Monitoring

- **Node Statistics** - Online/offline counts, solar percentage
- **GPU Metrics** - Utilization, memory, model loading
- **Energy Tracking** - Solar, grid, battery sources
- **Performance Metrics** - Request rate, P99 latency
- **Geozone Stats** - Regional breakdowns

## Configuration

### Environment Variables

Create a `.env` file:

```bash
# WebSocket URL (leave empty for mock mode)
VITE_WS_URL=ws://localhost:8080/telemetry

# Enable debug logging
VITE_DEBUG_TELEMETRY=true
```

See [.env.example](.env.example) for all options.

## Architecture

### Tech Stack

- **React 18.3** - UI framework
- **TypeScript 5.6** - Type safety
- **Vite 5.4** - Build tool and dev server
- **Zustand** - State management with Immer
- **Deck.gl** - WebGL-powered visualizations
- **CSS Modules** - Scoped styling

### Project Structure

```
arcnet-console/
├── src/
│   ├── components/      # React components
│   │   ├── Globe/       # 3D globe visualization
│   │   ├── Header/      # Console header
│   │   ├── CommandLine/ # CLI interface
│   │   ├── ConnectionStatus/ # WebSocket status
│   │   └── ...
│   ├── hooks/           # Custom React hooks
│   │   ├── useWebSocket.ts        # WebSocket connection
│   │   ├── useArcnetTelemetry.ts  # Protocol parser
│   │   └── useMockTelemetry.ts    # Mock data
│   ├── stores/          # Zustand stores
│   │   └── arcnetStore.ts
│   ├── types/           # TypeScript types
│   │   └── arcnet.ts
│   ├── pages/           # Page components
│   │   └── CLIDemo.tsx
│   └── layouts/         # Layout components
├── docs/                # Documentation
│   ├── TELEMETRY_INTEGRATION.md
│   ├── CLI_GUIDE.md
│   └── ...
├── public/              # Static assets
└── package.json
```

## Documentation

- **[Telemetry Integration](docs/TELEMETRY_INTEGRATION.md)** - WebSocket and mock data
- **[CLI Guide](docs/CLI_GUIDE.md)** - Command reference
- **[CLI Access](docs/CLI_ACCESS.md)** - CLI modes and navigation
- **[Feature Guide](FEATURE_GUIDE.md)** - Complete feature overview
- **[Testing Checklist](TESTING_CHECKLIST.md)** - QA checklist

## Development

### Scripts

```bash
npm run dev      # Start dev server
npm run build    # Production build
npm run preview  # Preview production build
npm run lint     # Run ESLint
```

### Hot Module Replacement

Vite provides instant HMR for:
- React components
- CSS modules
- TypeScript files
- Environment variables (requires restart)

### Debugging

Enable debug logging:
```bash
export VITE_DEBUG_TELEMETRY=true
npm run dev
```

Check browser console for:
- `[WebSocket]` - Connection events
- `[Telemetry]` - Message parsing
- `[MockTelemetry]` - Simulation events

## Testing

### Manual Testing

See [TESTING_CHECKLIST.md](TESTING_CHECKLIST.md) for comprehensive QA checklist.

### Browser Compatibility

- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+

WebGL 2.0 required for globe visualization.

## Deployment

### Build

```bash
npm run build
```

Output: `dist/` directory

### Serve

```bash
npm run preview
```

Or use any static file server:
```bash
npx serve dist
```

### Environment Variables

Set `VITE_WS_URL` at build time or runtime:

**Build time:**
```bash
VITE_WS_URL=wss://telemetry.arcnet.io npm run build
```

**Runtime (Docker):**
```dockerfile
ENV VITE_WS_URL=wss://telemetry.arcnet.io
```

## Contributing

### Code Style

- TypeScript strict mode
- ESLint + Prettier
- CSS Modules for styling
- Functional components with hooks

### Component Guidelines

- Use TypeScript interfaces for props
- Export types alongside components
- Document complex logic with comments
- Keep components focused and small

## License

See LICENSE file in repository root.

## Related Projects

- **arcnet-protocol** - Clojure backend with Kafka and XTDB
- **arcnet-sim** - Network simulation and load testing

## Support

For issues and questions:
- Check [docs/](docs/) directory
- Review [FEATURE_GUIDE.md](FEATURE_GUIDE.md)
- Open an issue on GitHub
