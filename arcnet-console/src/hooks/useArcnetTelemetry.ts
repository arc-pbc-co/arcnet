/**
 * useArcnetTelemetry - Parse WebSocket messages and update store
 * 
 * Handles message types from arcnet-protocol:
 * - telemetry: Node telemetry updates
 * - inference: Inference request events
 * - hpc: HPC transfer events
 * - system: System events and alerts
 */

import { useCallback } from 'react';
import { useWebSocket, type ConnectionState } from './useWebSocket';
import { useArcnetStore, type ArcnetState, type ArcnetActions } from '@/stores/arcnetStore';
import type { WebSocketMessage, Node, InferenceArc, HpcTransfer, ConsoleEvent, Position } from '@/types/arcnet';
import { ORNL_POSITION } from '@/types/arcnet';

/** Store type for message handlers */
type ArcnetStore = ArcnetState & ArcnetActions;

export interface UseArcnetTelemetryOptions {
  /** WebSocket URL (null to disable) */
  url: string | null;
  /** Enable debug logging */
  debug?: boolean;
}

export interface UseArcnetTelemetryReturn {
  /** Connection state */
  connectionState: ConnectionState;
  /** Manually reconnect */
  reconnect: () => void;
  /** Manually disconnect */
  disconnect: () => void;
}

/**
 * Converts geohash to approximate [lng, lat] position
 * This is a simplified conversion - in production, use a proper geohash library
 */
function geohashToPosition(_geohash: string): [number, number] {
  // Simplified geohash decoding for demo purposes
  // In production, use a proper geohash library like 'ngeohash'
  const lat = 37.7749 + (Math.random() - 0.5) * 20; // Rough approximation
  const lng = -122.4194 + (Math.random() - 0.5) * 40;
  return [lng, lat];
}

/**
 * Converts protocol energy source to frontend type
 */
function mapEnergySource(source: string): 'cogen' | 'grid' | 'battery' {
  const normalized = source.toLowerCase();
  // Map 'solar' to 'cogen' for backwards compatibility
  if (normalized === 'solar' || normalized === 'cogen') {
    return 'cogen';
  }
  if (normalized === 'grid' || normalized === 'battery') {
    return normalized as 'grid' | 'battery';
  }
  return 'grid'; // Default fallback
}

export function useArcnetTelemetry(options: UseArcnetTelemetryOptions): UseArcnetTelemetryReturn {
  const { url, debug = false } = options;
  const store = useArcnetStore();

  const handleMessage = useCallback((data: unknown) => {
    if (!data || typeof data !== 'object') {
      if (debug) console.warn('[Telemetry] Invalid message:', data);
      return;
    }

    const message = data as WebSocketMessage;

    try {
      switch (message.type) {
        case 'telemetry':
          handleTelemetryMessage(message, store, debug);
          break;
        case 'inference':
          handleInferenceMessage(message, store, debug);
          break;
        case 'hpc':
          handleHpcMessage(message, store, debug);
          break;
        case 'system':
          handleSystemMessage(message, store, debug);
          break;
        default:
          if (debug) console.warn('[Telemetry] Unknown message type:', message);
      }
    } catch (error) {
      console.error('[Telemetry] Error processing message:', error, message);
    }
  }, [store, debug]);

  const handleOpen = useCallback(() => {
    console.log('[Telemetry] Connected to ArcNet telemetry stream');
    store.setConnected(true);
  }, [store]);

  const handleClose = useCallback(() => {
    console.log('[Telemetry] Disconnected from ArcNet telemetry stream');
    store.setConnected(false);
  }, [store]);

  const handleError = useCallback((error: Event) => {
    console.error('[Telemetry] WebSocket error:', error);
    store.setConnected(false);
  }, [store]);

  const { state, reconnect, disconnect } = useWebSocket({
    url,
    autoReconnect: true,
    reconnectDelay: 2000,
    maxReconnectDelay: 30000,
    heartbeatInterval: 30000,
    onOpen: handleOpen,
    onClose: handleClose,
    onError: handleError,
    onMessage: handleMessage,
  });

  return {
    connectionState: state,
    reconnect,
    disconnect,
  };
}

// =============================================================================
// Message Handlers
// =============================================================================

function handleTelemetryMessage(
  message: WebSocketMessage & { type: 'telemetry' },
  store: ArcnetStore,
  debug: boolean
) {
  const { node: telemetry } = message;

  if (debug) console.log('[Telemetry] Node update:', telemetry.id);

  // Find existing node or create new one
  const existingNode = store.nodes.find(n => n.id === telemetry.id);

  const geohash = telemetry.geohash || '9q8yyk';
  const position = existingNode?.position || geohashToPosition(geohash);

  const updatedNode: Node = {
    id: telemetry.id,
    name: existingNode?.name || `Node-${telemetry.id.slice(0, 8)}`,
    geohash,
    position,
    geozone: existingNode?.geozone || geohash.slice(0, 4) || 'CAISO',
    status: telemetry.batteryLevel < 0.1 ? 'offline' :
            telemetry.gpuUtilization > 0.8 ? 'busy' :
            telemetry.gpuUtilization > 0.3 ? 'online' : 'idle',
    energySource: mapEnergySource(telemetry.energySource),
    batteryLevel: telemetry.batteryLevel,
    gpuUtilization: telemetry.gpuUtilization,
    gpuMemoryFreeGb: telemetry.gpuMemoryFreeGb,
    gpuCount: existingNode?.gpuCount || 8,
    gpuMemoryTotalGb: existingNode?.gpuMemoryTotalGb || 80,
    modelsLoaded: telemetry.modelsLoaded || [],
    lastSeen: new Date(telemetry.timestamp),
  };

  // Update or add node
  if (existingNode) {
    store.updateNode(telemetry.id, updatedNode);
  } else {
    store.addNode(updatedNode);
  }
}

function handleInferenceMessage(
  message: WebSocketMessage & { type: 'inference' },
  store: ArcnetStore,
  debug: boolean
) {
  if (debug) console.log('[Telemetry] Inference event:', message.requestId);

  // Find target node position for arc visualization
  const targetNode = store.nodes.find((n: Node) => n.id === message.targetNodeId);
  const targetPosition: Position = targetNode?.position || [0, 0];

  // Add inference arc for visualization
  const arc: InferenceArc = {
    id: message.requestId,
    source: message.source,
    target: targetPosition,
    targetNodeId: message.targetNodeId,
    modelId: message.modelId,
    priority: message.priority,
    status: message.status,
    latencyMs: message.latencyMs,
    timestamp: new Date(message.timestamp),
    progress: message.status === 'completed' ? 1.0 : 0.0,
  };

  store.addInferenceArc(arc);

  // Add event
  store.addEvent({
    id: `inf-${message.requestId}`,
    type: 'inference',
    severity: message.status === 'failed' ? 'error' : 'info',
    message: `Inference ${message.status}: ${message.modelId}`,
    timestamp: new Date(message.timestamp),
  });
}

function handleHpcMessage(
  message: WebSocketMessage & { type: 'hpc' },
  store: ArcnetStore,
  debug: boolean
) {
  if (debug) console.log('[Telemetry] HPC event:', message.jobId);

  // Find source node position for arc visualization
  const sourceNode = store.nodes.find((n: Node) => n.id === message.sourceNodeId);
  const sourcePosition: Position = sourceNode?.position || [0, 0];

  const transfer: HpcTransfer = {
    id: message.jobId,
    sourceNodeId: message.sourceNodeId,
    source: sourcePosition,
    target: ORNL_POSITION,
    datasetSizeGb: message.datasetSizeGb,
    status: message.status,
    progress: message.progress,
    bytesTransferred: message.bytesTransferred,
    timestamp: new Date(message.timestamp),
  };

  // Check if transfer exists
  const existing = store.hpcTransfers.find(t => t.id === message.jobId);
  if (existing) {
    store.updateHpcTransfer(message.jobId, transfer);
  } else {
    store.addHpcTransfer(transfer);
  }
}

function handleSystemMessage(
  message: WebSocketMessage & { type: 'system' },
  store: ArcnetStore,
  debug: boolean
) {
  if (debug) console.log('[Telemetry] System event:', message.message);

  const event: ConsoleEvent = {
    id: `sys-${Date.now()}`,
    type: 'system',
    severity: message.severity,
    message: message.message,
    timestamp: new Date(message.timestamp),
  };

  store.addEvent(event);
}

