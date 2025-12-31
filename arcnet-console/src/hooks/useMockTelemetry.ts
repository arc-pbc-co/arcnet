/**
 * useMockTelemetry - Simulate telemetry when no WebSocket URL is available
 * 
 * Generates realistic telemetry data matching arcnet-protocol schema:
 * - Node telemetry updates (battery, GPU, energy source)
 * - Inference request events
 * - HPC transfer events
 * - System events
 */

import { useEffect, useRef } from 'react';
import { useArcnetStore } from '@/stores/arcnetStore';
import type { Node, InferenceArc, HpcTransfer, Position } from '@/types/arcnet';

// ORNL (Oak Ridge National Laboratory) coordinates
const ORNL_POSITION: Position = [-84.2696, 35.9311];

export interface UseMockTelemetryOptions {
  /** Enable mock telemetry */
  enabled: boolean;
  /** Telemetry update interval in ms (default: 5000) */
  interval?: number;
  /** Enable debug logging */
  debug?: boolean;
}

const GEOZONES = ['CAISO', 'ERCOT', 'PJM', 'MISO', 'SPP', 'NYISO'];
const MODELS = ['llama-3.1-70b', 'gpt-4', 'claude-3-opus', 'mistral-large', 'gemini-pro'];
const ENERGY_SOURCES: Array<'cogen' | 'grid' | 'battery'> = ['cogen', 'grid', 'battery'];

// Sample geohashes for different US regions
const SAMPLE_GEOHASHES = [
  '9q8yyk', // San Francisco
  '9q5ctr', // Los Angeles
  '9xj64r', // Denver
  'dp3wjz', // Chicago
  'drt2yz', // New York
  '9vg4hd', // Seattle
  '9tbwxe', // Portland
  'djd54g', // Dallas
  'dhvd1g', // Houston
  '9yf0bj', // Phoenix
];

/**
 * Generate random position within US bounds
 */
function randomPosition(): [number, number] {
  const lng = -125 + Math.random() * 50; // -125 to -75 (US longitude)
  const lat = 25 + Math.random() * 25;   // 25 to 50 (US latitude)
  return [lng, lat];
}

/**
 * Generate mock node telemetry
 */
function generateMockNode(id: string): Node {
  const energySource = ENERGY_SOURCES[Math.floor(Math.random() * ENERGY_SOURCES.length)];
  const batteryLevel = energySource === 'grid' ? 1.0 : 0.3 + Math.random() * 0.7;
  const gpuUtilization = Math.random();
  const geohash = SAMPLE_GEOHASHES[Math.floor(Math.random() * SAMPLE_GEOHASHES.length)];

  return {
    id,
    name: `Node-${id.slice(0, 8)}`,
    geohash,
    position: randomPosition(),
    geozone: GEOZONES[Math.floor(Math.random() * GEOZONES.length)],
    status: batteryLevel < 0.1 ? 'offline' :
            gpuUtilization > 0.8 ? 'busy' :
            gpuUtilization > 0.3 ? 'online' : 'idle',
    energySource,
    batteryLevel,
    gpuUtilization,
    gpuMemoryFreeGb: 10 + Math.random() * 70,
    gpuCount: 8,
    gpuMemoryTotalGb: 80,
    modelsLoaded: Array.from(
      { length: Math.floor(Math.random() * 3) },
      () => MODELS[Math.floor(Math.random() * MODELS.length)]
    ),
    lastSeen: new Date(),
  };
}

/**
 * Update node telemetry with realistic changes
 */
function updateNodeTelemetry(node: Node): Node {
  const hour = new Date().getHours();
  const isDaytime = hour >= 6 && hour <= 18;

  // Update battery based on energy source and time
  let batteryDelta = 0;
  if (node.energySource === 'cogen') {
    batteryDelta = isDaytime ? 0.02 : -0.01; // Charge during day, drain at night
  } else if (node.energySource === 'battery') {
    batteryDelta = -0.005; // Slow drain
  }

  const newBattery = Math.max(0, Math.min(1, node.batteryLevel + batteryDelta));

  // Random walk for GPU utilization
  const gpuDelta = (Math.random() - 0.5) * 0.1;
  const newGpuUtil = Math.max(0, Math.min(1, node.gpuUtilization + gpuDelta));

  // Update GPU memory
  const newGpuMemory = Math.max(5, Math.min(75, node.gpuMemoryFreeGb + (Math.random() - 0.5) * 10));

  return {
    ...node,
    batteryLevel: newBattery,
    gpuUtilization: newGpuUtil,
    gpuMemoryFreeGb: newGpuMemory,
    status: newBattery < 0.1 ? 'offline' :
            newGpuUtil > 0.8 ? 'busy' :
            newGpuUtil > 0.3 ? 'online' : 'idle',
    lastSeen: new Date(),
  };
}

/**
 * Generate mock inference event
 */
function generateMockInference(nodes: Node[]): InferenceArc | null {
  if (nodes.length === 0) return null;

  const targetNode = nodes[Math.floor(Math.random() * nodes.length)];
  const model = MODELS[Math.floor(Math.random() * MODELS.length)];

  return {
    id: `inf-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
    source: randomPosition(),
    target: targetNode.position,
    targetNodeId: targetNode.id,
    modelId: model,
    priority: Math.random() > 0.8 ? 'critical' : Math.random() > 0.5 ? 'normal' : 'background',
    status: Math.random() > 0.9 ? 'failed' : Math.random() > 0.5 ? 'completed' : 'dispatched',
    latencyMs: 50 + Math.random() * 200,
    timestamp: new Date(),
    progress: Math.random(),
  };
}

/**
 * Generate mock HPC transfer event
 */
function generateMockHpcTransfer(nodes: Node[]): HpcTransfer | null {
  if (nodes.length === 0) return null;

  const sourceNode = nodes[Math.floor(Math.random() * nodes.length)];
  const datasetSize = 10 + Math.random() * 500;
  const progress = Math.random();

  return {
    id: `hpc-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
    sourceNodeId: sourceNode.id,
    source: sourceNode.position,
    target: ORNL_POSITION,
    datasetSizeGb: datasetSize,
    status: progress < 0.3 ? 'queued' : progress < 0.7 ? 'transferring' : progress < 0.9 ? 'running' : 'completed',
    progress,
    bytesTransferred: datasetSize * progress * 1024 * 1024 * 1024,
    timestamp: new Date(),
  };
}

export function useMockTelemetry(options: UseMockTelemetryOptions): void {
  const { enabled, interval = 5000, debug = false } = options;
  const store = useArcnetStore();
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const initializedRef = useRef(false);

  useEffect(() => {
    if (!enabled) {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
      return;
    }

    // Initialize with mock nodes
    if (!initializedRef.current) {
      if (debug) console.log('[MockTelemetry] Initializing with mock nodes');
      
      const mockNodes = Array.from({ length: 20 }, (_, i) => 
        generateMockNode(`mock-node-${i}`)
      );
      
      mockNodes.forEach(node => store.addNode(node));
      store.setConnected(true); // Mark as "connected" to mock data
      initializedRef.current = true;
    }

    // Start telemetry updates
    intervalRef.current = setInterval(() => {
      if (debug) console.log('[MockTelemetry] Generating telemetry update');
      
      // Update all nodes
      store.nodes.forEach(node => {
        const updated = updateNodeTelemetry(node);
        store.updateNode(node.id, updated);
      });
      
      // Randomly generate inference events (30% chance)
      if (Math.random() > 0.7) {
        const inference = generateMockInference(store.nodes);
        if (inference) {
          store.addInferenceArc(inference);
          store.addEvent({
            id: `evt-${inference.id}`,
            type: 'inference',
            severity: inference.status === 'failed' ? 'error' : 'info',
            message: `Inference ${inference.status}: ${inference.modelId}`,
            timestamp: new Date(),
          });
        }
      }
      
      // Randomly generate HPC events (10% chance)
      if (Math.random() > 0.9) {
        const hpc = generateMockHpcTransfer(store.nodes);
        if (hpc) {
          store.addHpcTransfer(hpc);
          store.addEvent({
            id: `evt-${hpc.id}`,
            type: 'hpc',
            severity: 'info',
            message: `HPC transfer ${hpc.status}: ${hpc.datasetSizeGb.toFixed(1)} GB`,
            timestamp: new Date(),
          });
        }
      }
    }, interval);

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
    };
  }, [enabled, interval, debug, store]);
}

