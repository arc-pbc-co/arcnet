# Telemetry Quick Start Guide

Get up and running with ArcNet telemetry in 5 minutes.

## 🚀 Quick Start

### Option 1: Mock Mode (Default)

```bash
cd arcnet-console
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000)

✅ **That's it!** The console will show simulated telemetry data.

### Option 2: Live WebSocket

```bash
# 1. Configure WebSocket URL
echo "VITE_WS_URL=ws://localhost:8080/telemetry" > .env

# 2. Start console
npm run dev
```

Open [http://localhost:3000](http://localhost:3000)

✅ Connection status will show **LIVE** (green) when connected.

## 📊 What You'll See

### Mock Mode
- 🔵 **MOCK** indicator in header (blue)
- 20 nodes on the globe
- Updates every 5 seconds
- Realistic battery/GPU simulation
- Random inference events
- Random HPC transfers

### Live Mode
- 🟢 **LIVE** indicator in header (green pulse)
- Real-time node updates
- Actual inference routing
- HPC transfer tracking
- System events

## 🎛️ Configuration

### Environment Variables

Create `.env` file:

```bash
# WebSocket URL (leave empty for mock mode)
VITE_WS_URL=ws://localhost:8080/telemetry

# Enable debug logging
VITE_DEBUG_TELEMETRY=true
```

### Connection States

| State | Color | Meaning | Action |
|-------|-------|---------|--------|
| 🟢 LIVE | Green | Connected | None |
| 🟡 CONNECTING | Yellow | Connecting... | Wait |
| 🔴 OFFLINE | Red | Disconnected | Click to reconnect |
| 🔵 MOCK | Blue | Simulated data | None |

## 🔧 Development

### Enable Debug Logging

```bash
export VITE_DEBUG_TELEMETRY=true
npm run dev
```

Check browser console for:
- `[WebSocket]` - Connection events
- `[Telemetry]` - Message parsing
- `[MockTelemetry]` - Simulation events

### Test WebSocket Connection

```bash
# Terminal 1: Start WebSocket server
cd arcnet-protocol
lein run -m arcnet.gateway.websocket

# Terminal 2: Start console
cd arcnet-console
echo "VITE_WS_URL=ws://localhost:8080/telemetry" > .env
npm run dev
```

### Switch Between Modes

**To Mock Mode:**
```bash
# Remove or comment out VITE_WS_URL
echo "# VITE_WS_URL=" > .env
npm run dev
```

**To Live Mode:**
```bash
echo "VITE_WS_URL=ws://localhost:8080/telemetry" > .env
npm run dev
```

## 📡 Message Format

### Send Telemetry (from server)

```json
{
  "type": "telemetry",
  "node": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2024-01-15T10:30:00Z",
    "geohash": "9q8yyk",
    "energySource": "solar",
    "batteryLevel": 0.85,
    "gpuUtilization": 0.65,
    "gpuMemoryFreeGb": 45.2,
    "modelsLoaded": ["llama-3.1-70b", "gpt-4"]
  }
}
```

### Send Inference Event

```json
{
  "type": "inference",
  "requestId": "req-12345",
  "source": [-122.4194, 37.7749],
  "targetNodeId": "node-abc",
  "modelId": "llama-3.1-70b",
  "priority": "normal",
  "status": "completed",
  "latencyMs": 125,
  "timestamp": "2024-01-15T10:30:00Z"
}
```

## 🐛 Troubleshooting

### No nodes appearing

**Mock Mode:**
```bash
# Check console for errors
# Verify store has nodes
console.log(useArcnetStore.getState().nodes)
```

**Live Mode:**
```bash
# Check WebSocket connection
# Browser DevTools → Network → WS tab
# Verify server is sending messages
```

### Connection keeps dropping

```bash
# Check server logs
# Verify network stability
# Try increasing reconnect delay:
# Edit src/hooks/useArcnetTelemetry.ts
# Change reconnectDelay: 5000
```

### Messages not parsing

```bash
# Enable debug logging
export VITE_DEBUG_TELEMETRY=true
npm run dev

# Check browser console for parsing errors
# Verify message format matches protocol
```

## 📚 Next Steps

- **[Full Documentation](TELEMETRY_INTEGRATION.md)** - Complete integration guide
- **[Implementation Summary](TELEMETRY_IMPLEMENTATION_SUMMARY.md)** - Technical details
- **[CLI Guide](CLI_GUIDE.md)** - Command line interface

## 🎯 Common Tasks

### Add Custom Message Type

1. Update `src/types/arcnet.ts`:
```typescript
export interface CustomMessage {
  type: 'custom';
  data: string;
}

export type WebSocketMessage = 
  | TelemetryMessage 
  | InferenceEventMessage
  | CustomMessage;  // Add here
```

2. Add handler in `src/hooks/useArcnetTelemetry.ts`:
```typescript
case 'custom':
  handleCustomMessage(message, store, debug);
  break;
```

### Change Update Interval

Edit `src/App.tsx`:
```typescript
useMockTelemetry({
  enabled: isMockMode,
  interval: 10000,  // Change from 5000 to 10000ms
  debug: DEBUG_TELEMETRY,
});
```

### Add Connection Metrics

Edit `src/hooks/useWebSocket.ts`:
```typescript
// Track metrics
const [metrics, setMetrics] = useState({
  messagesReceived: 0,
  lastMessageTime: null,
  avgLatency: 0,
});

ws.onmessage = (event) => {
  setMetrics(prev => ({
    ...prev,
    messagesReceived: prev.messagesReceived + 1,
    lastMessageTime: Date.now(),
  }));
  // ... rest of handler
};
```

## ✅ Checklist

Before deploying:

- [ ] Test mock mode works
- [ ] Test live mode connects
- [ ] Test auto-reconnect works
- [ ] Test manual reconnect works
- [ ] Verify all message types parse
- [ ] Check connection status indicator
- [ ] Test on mobile/tablet
- [ ] Verify no console errors
- [ ] Test with slow network
- [ ] Test with connection loss

## 🎉 You're Ready!

The telemetry system is now integrated and ready to use. Check the header for connection status and watch the globe come alive with real-time data!

For questions or issues, see the [full documentation](TELEMETRY_INTEGRATION.md).

