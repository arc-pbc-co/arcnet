# ArcNet Telemetry Integration

Real-time data streaming integration for the ArcNet Console, supporting both live WebSocket connections and mock telemetry mode.

## Overview

The telemetry system provides real-time updates for:
- **Node Telemetry**: Battery levels, GPU utilization, energy sources, models loaded
- **Inference Events**: Request dispatching, completion, failures
- **HPC Transfers**: Dataset transfers to supercomputing facilities
- **System Events**: Alerts, status changes, network events

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         App.tsx                             │
│  ┌────────────────────┐      ┌────────────────────┐        │
│  │ useArcnetTelemetry │      │ useMockTelemetry   │        │
│  │ (Real WebSocket)   │      │ (Simulated Data)   │        │
│  └─────────┬──────────┘      └─────────┬──────────┘        │
│            │                           │                    │
│            └───────────┬───────────────┘                    │
│                        │                                    │
│                        ▼                                    │
│              ┌──────────────────┐                           │
│              │  ArcnetStore     │                           │
│              │  (Zustand)       │                           │
│              └──────────────────┘                           │
│                        │                                    │
│         ┌──────────────┼──────────────┐                    │
│         ▼              ▼              ▼                    │
│    ┌────────┐    ┌─────────┐    ┌─────────┐              │
│    │ Globe  │    │ Panels  │    │ Events  │              │
│    └────────┘    └─────────┘    └─────────┘              │
└─────────────────────────────────────────────────────────────┘
```

## Configuration

### Environment Variables

Create a `.env` file in `arcnet-console/`:

```bash
# WebSocket URL for real-time telemetry
# Leave empty to use mock mode
VITE_WS_URL=ws://localhost:8080/telemetry

# Enable debug logging
VITE_DEBUG_TELEMETRY=true
```

### Mock Mode vs Live Mode

**Mock Mode** (default):
- Activated when `VITE_WS_URL` is not set
- Generates realistic simulated data
- 20 nodes with random positions
- Updates every 5 seconds
- Perfect for development and demos

**Live Mode**:
- Activated when `VITE_WS_URL` is set
- Connects to real WebSocket server
- Auto-reconnect with exponential backoff
- Parses messages from arcnet-protocol

## Hooks

### useWebSocket

Low-level WebSocket connection with auto-reconnect.

```typescript
import { useWebSocket } from '@/hooks/useWebSocket';

const { state, send, reconnect, disconnect } = useWebSocket({
  url: 'ws://localhost:8080',
  autoReconnect: true,
  reconnectDelay: 1000,
  maxReconnectDelay: 30000,
  heartbeatInterval: 30000,
  onOpen: () => console.log('Connected'),
  onClose: () => console.log('Disconnected'),
  onError: (error) => console.error('Error:', error),
  onMessage: (data) => console.log('Message:', data),
});
```

**Features:**
- Automatic reconnection with exponential backoff
- Message queue during disconnection
- Heartbeat/ping support
- Connection state management

### useArcnetTelemetry

High-level hook that parses ArcNet protocol messages and updates the store.

```typescript
import { useArcnetTelemetry } from '@/hooks/useArcnetTelemetry';

const { connectionState, reconnect, disconnect } = useArcnetTelemetry({
  url: 'ws://localhost:8080/telemetry',
  debug: true,
});
```

**Message Types:**
- `telemetry`: Node telemetry updates
- `inference`: Inference request events
- `hpc`: HPC transfer events
- `system`: System events and alerts

### useMockTelemetry

Simulates telemetry when no WebSocket URL is available.

```typescript
import { useMockTelemetry } from '@/hooks/useMockTelemetry';

useMockTelemetry({
  enabled: true,
  interval: 5000,
  debug: false,
});
```

**Simulation Features:**
- 20 nodes across US regions
- Solar charging during daytime (6am-6pm)
- Battery drain at night
- GPU utilization random walk
- Random inference events (30% chance per update)
- Random HPC events (10% chance per update)

## Message Protocol

### Telemetry Message

```typescript
{
  type: 'telemetry',
  node: {
    id: string,              // UUID
    timestamp: string,       // ISO 8601
    geohash: string,         // 6-char geohash
    energySource: 'solar' | 'grid' | 'battery',
    batteryLevel: number,    // 0.0 to 1.0
    gpuUtilization: number,  // 0.0 to 1.0
    gpuMemoryFreeGb: number,
    modelsLoaded: string[],
  }
}
```

### Inference Event Message

```typescript
{
  type: 'inference',
  requestId: string,
  source: [number, number],  // [lng, lat]
  targetNodeId: string,
  modelId: string,
  priority: 'critical' | 'normal' | 'background',
  status: 'dispatched' | 'completed' | 'failed',
  latencyMs?: number,
  timestamp: string,
}
```

### HPC Event Message

```typescript
{
  type: 'hpc',
  jobId: string,
  sourceNodeId: string,
  datasetSizeGb: number,
  status: 'queued' | 'transferring' | 'running' | 'completed',
  progress: number,         // 0.0 to 1.0
  bytesTransferred: number,
  timestamp: string,
}
```

### System Event Message

```typescript
{
  type: 'system',
  severity: 'info' | 'warning' | 'error' | 'critical',
  message: string,
  details?: Record<string, unknown>,
  timestamp: string,
}
```

## Connection Status Indicator

The `ConnectionStatus` component shows real-time connection state in the header.

**States:**
- 🟢 **LIVE** - Connected to WebSocket (green pulse)
- 🟡 **CONNECTING** - Attempting connection (yellow pulse)
- 🔴 **OFFLINE** - Disconnected (red, clickable to reconnect)
- 🔵 **MOCK** - Using simulated data (blue)

**Location:** Top-right corner of the header, next to the clock

**Interaction:** Click on OFFLINE/ERROR states to manually reconnect

## Store Integration

All telemetry data flows through the Zustand store (`useArcnetStore`).

**Actions Used:**
- `addNode(node)` - Add new node
- `updateNode(id, updates)` - Update existing node
- `addInferenceArc(arc)` - Add inference visualization
- `addHpcTransfer(transfer)` - Add HPC transfer
- `updateHpcTransfer(id, updates)` - Update HPC transfer
- `addEvent(event)` - Add event to log
- `setConnected(boolean)` - Update connection status

## Development

### Running with Mock Data

```bash
cd arcnet-console
npm run dev
```

The console will start in mock mode by default.

### Running with Live WebSocket

1. Start your WebSocket server:
```bash
# Example: arcnet-protocol WebSocket gateway
cd arcnet-protocol
lein run -m arcnet.gateway.websocket
```

2. Configure environment:
```bash
echo "VITE_WS_URL=ws://localhost:8080/telemetry" > .env
```

3. Start console:
```bash
npm run dev
```

### Debugging

Enable debug logging:
```bash
echo "VITE_DEBUG_TELEMETRY=true" >> .env
```

Check browser console for:
- `[WebSocket]` - Connection events
- `[Telemetry]` - Message parsing
- `[MockTelemetry]` - Simulation events

## Testing

### Manual Testing Checklist

- [ ] Mock mode starts automatically without VITE_WS_URL
- [ ] Connection status shows "MOCK" in blue
- [ ] Nodes appear on globe within 5 seconds
- [ ] Node telemetry updates every 5 seconds
- [ ] Battery levels change based on time of day
- [ ] GPU utilization fluctuates
- [ ] Inference arcs appear occasionally
- [ ] Events appear in event log
- [ ] Setting VITE_WS_URL switches to live mode
- [ ] Connection status shows "CONNECTING" then "LIVE"
- [ ] Disconnecting shows "OFFLINE" (clickable)
- [ ] Clicking "OFFLINE" attempts reconnection
- [ ] Auto-reconnect works after connection loss

## Troubleshooting

### No nodes appearing

**Mock Mode:**
- Check browser console for errors
- Verify `useMockTelemetry` is enabled
- Check store state: `useArcnetStore.getState().nodes`

**Live Mode:**
- Verify WebSocket URL is correct
- Check WebSocket server is running
- Check browser console for connection errors
- Verify message format matches protocol

### Connection keeps dropping

- Check network stability
- Verify WebSocket server health
- Increase `maxReconnectDelay` in `useWebSocket`
- Check firewall/proxy settings

### Messages not parsing

- Enable debug logging: `VITE_DEBUG_TELEMETRY=true`
- Check message format in browser console
- Verify schema version compatibility
- Check for JSON parsing errors

## Future Enhancements

- [ ] Message compression (gzip/brotli)
- [ ] Binary protocol support (MessagePack)
- [ ] Message batching for performance
- [ ] Selective subscriptions (filter by geozone)
- [ ] Historical data replay
- [ ] WebSocket authentication
- [ ] TLS/WSS support
- [ ] Metrics dashboard for telemetry health

## Related Files

- `src/hooks/useWebSocket.ts` - WebSocket connection
- `src/hooks/useArcnetTelemetry.ts` - Protocol parser
- `src/hooks/useMockTelemetry.ts` - Mock data generator
- `src/components/ConnectionStatus/` - Status indicator
- `src/stores/arcnetStore.ts` - State management
- `src/types/arcnet.ts` - Type definitions
- `.env.example` - Environment template

