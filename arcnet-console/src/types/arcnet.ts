/**
 * ARCNet Console Type Definitions
 *
 * Core types for the distributed AI network operations dashboard.
 * Matches the schema definitions in arcnet-protocol.
 */

// =============================================================================
// Energy and Status Types
// =============================================================================

/** Energy source powering a node */
export type EnergySource = 'cogen' | 'grid' | 'battery';

/** Priority levels for inference requests */
export type Priority = 'critical' | 'normal' | 'background';

/** Node operational status */
export type NodeStatus = 'online' | 'busy' | 'idle' | 'stale' | 'offline';

/** HPC job status */
export type HpcJobStatus = 'pending' | 'transferring' | 'queued' | 'running' | 'completed' | 'failed';

/** Inference request status */
export type InferenceStatus = 'dispatched' | 'processing' | 'completed' | 'failed' | 'rejected';

// =============================================================================
// Node Types
// =============================================================================

/** Geographic position as [longitude, latitude] */
export type Position = [number, number];

/**
 * A compute node in the ARCNet mesh.
 * Corresponds to NodeTelemetry schema v2.
 */
export interface Node {
  /** Unique node identifier (UUID) */
  id: string;

  /** Human-readable node name (e.g., "rubin-west-042") */
  name: string;

  /** 6-character geohash for location */
  geohash: string;

  /** Geographic coordinates [longitude, latitude] */
  position: Position;

  /** Geozone identifier (e.g., "us-west", "eu-central") */
  geozone: string;

  /** Energy source powering this node */
  energySource: EnergySource;

  /** Battery level (0.0 to 1.0) */
  batteryLevel: number;

  /** GPU utilization (0.0 to 1.0) */
  gpuUtilization: number;

  /** Free GPU memory in GB */
  gpuMemoryFreeGb: number;

  /** Total GPU memory in GB */
  gpuMemoryTotalGb: number;

  /** Number of GPUs in this node */
  gpuCount: number;

  /** Model IDs currently loaded on this node */
  modelsLoaded: string[];

  /** Current operational status */
  status: NodeStatus;

  /** Last telemetry timestamp */
  lastSeen: Date;

  /** Active reservation (if any) */
  reservation?: {
    requestId: string;
    expiresAt: Date;
  };

  /** Whether this node is a central hub (e.g., ORNL Frontier) */
  isHub?: boolean;
}

/**
 * Aggregated statistics for a geozone.
 */
export interface GeozoneStats {
  /** Geozone identifier */
  id: string;

  /** Total nodes in geozone */
  totalNodes: number;

  /** Active (non-stale) nodes */
  activeNodes: number;

  /** Total available GPUs */
  availableGpus: number;

  /** Average battery level across nodes */
  avgBatteryLevel: number;

  /** Average GPU utilization */
  avgGpuUtilization: number;

  /** Nodes by energy source */
  nodesByEnergy: {
    cogen: number;
    grid: number;
    battery: number;
  };
}

// =============================================================================
// Inference Traffic Types
// =============================================================================

/**
 * An inference request being routed through the network.
 * Visualized as cyan arcs on the globe.
 */
export interface InferenceArc {
  /** Unique request identifier */
  id: string;

  /** Source position (requester location) [lng, lat] */
  source: Position;

  /** Target position (assigned node location) [lng, lat] */
  target: Position;

  /** Target node ID */
  targetNodeId: string;

  /** Model being queried */
  modelId: string;

  /** Request priority */
  priority: Priority;

  /** Current status */
  status: InferenceStatus;

  /** Latency in milliseconds (when completed) */
  latencyMs?: number;

  /** Timestamp of request initiation */
  timestamp: Date;

  /** Animation progress (0.0 to 1.0) for arc pulse */
  progress: number;
}

/**
 * Inference request input (before routing).
 */
export interface InferenceRequest {
  /** Unique request identifier */
  id: string;

  /** Model to query */
  modelId: string;

  /** Context window size in tokens */
  contextWindowTokens: number;

  /** Request priority */
  priority: Priority;

  /** Maximum acceptable latency in ms */
  maxLatencyMs: number;

  /** Requester's geozone */
  requesterGeozone: string;
}

// =============================================================================
// HPC Transfer Types
// =============================================================================

/**
 * An HPC data transfer to ORNL.
 * Visualized as purple arcs on the globe.
 */
export interface HpcTransfer {
  /** Job/transfer identifier */
  id: string;

  /** Source node ID */
  sourceNodeId: string;

  /** Source position [lng, lat] */
  source: Position;

  /** Target position (ORNL) [lng, lat] */
  target: Position;

  /** Dataset size in GB */
  datasetSizeGb: number;

  /** Current transfer status */
  status: HpcJobStatus;

  /** Transfer progress (0.0 to 1.0) */
  progress: number;

  /** Bytes transferred so far */
  bytesTransferred: number;

  /** Estimated completion time */
  estimatedCompletionTime?: Date;

  /** Timestamp of transfer initiation */
  timestamp: Date;

  /** Globus task ID (if available) */
  globusTaskId?: string;
}

/**
 * Training job submission.
 */
export interface TrainingJob {
  /** Job identifier */
  id: string;

  /** Dataset URI */
  datasetUri: string;

  /** Dataset size in GB */
  datasetSizeGb: number;

  /** Estimated FLOPS required */
  estimatedFlops: number;

  /** Checkpoint URI (optional) */
  checkpointUri?: string;

  /** Routing target (hpc or federated) */
  target: 'hpc' | 'federated';

  /** Routing reason */
  routingReason: string;
}

// =============================================================================
// Event Types
// =============================================================================

/** Event severity levels */
export type EventSeverity = 'info' | 'warn' | 'error' | 'success';

/** Event type categories */
export type EventType =
  | 'inference'
  | 'hpc'
  | 'node_online'
  | 'node_offline'
  | 'node_stale'
  | 'battery_low'
  | 'geozone_alert'
  | 'system';

/**
 * An event in the console log.
 */
export interface ConsoleEvent {
  /** Unique event identifier */
  id: string;

  /** Event timestamp */
  timestamp: Date;

  /** Event type category */
  type: EventType;

  /** Severity level */
  severity: EventSeverity;

  /** Short event message */
  message: string;

  /** Detailed event data */
  details?: Record<string, unknown>;

  /** Related node ID (if applicable) */
  nodeId?: string;

  /** Related request/job ID (if applicable) */
  relatedId?: string;
}

// =============================================================================
// WebSocket Message Types
// =============================================================================

/**
 * Telemetry update from a node.
 */
export interface TelemetryMessage {
  type: 'telemetry';
  node: Omit<Node, 'name' | 'position' | 'geozone' | 'gpuCount' | 'gpuMemoryTotalGb' | 'status'> & {
    id: string;
    timestamp: string;
  };
}

/**
 * Inference event (dispatch, completion, failure).
 */
export interface InferenceEventMessage {
  type: 'inference';
  requestId: string;
  source: Position;
  targetNodeId: string;
  modelId: string;
  priority: Priority;
  status: InferenceStatus;
  latencyMs?: number;
  timestamp: string;
}

/**
 * HPC transfer event.
 */
export interface HpcEventMessage {
  type: 'hpc';
  jobId: string;
  sourceNodeId: string;
  datasetSizeGb: number;
  status: HpcJobStatus;
  progress: number;
  bytesTransferred: number;
  timestamp: string;
}

/**
 * System event (alerts, status changes).
 */
export interface SystemEventMessage {
  type: 'system';
  severity: EventSeverity;
  message: string;
  details?: Record<string, unknown>;
  timestamp: string;
}

/** Union type for all WebSocket messages */
export type WebSocketMessage =
  | TelemetryMessage
  | InferenceEventMessage
  | HpcEventMessage
  | SystemEventMessage;

// =============================================================================
// Command Types
// =============================================================================

/**
 * A command entered in the console.
 */
export interface Command {
  /** Raw command string */
  raw: string;

  /** Parsed command name */
  name: string;

  /** Parsed arguments */
  args: string[];

  /** Parsed flags (e.g., --geozone west) */
  flags: Record<string, string | boolean>;

  /** Execution timestamp */
  timestamp: Date;
}

/**
 * Command execution result.
 */
export interface CommandResult {
  /** Whether command succeeded */
  success: boolean;

  /** Output message(s) */
  output: string[];

  /** Error message (if failed) */
  error?: string;

  /** Data payload (for commands that return data) */
  data?: unknown;
}

// =============================================================================
// Global Stats Types
// =============================================================================

/**
 * Global network statistics shown in header.
 */
export interface GlobalStats {
  /** Total nodes in network */
  totalNodes: number;

  /** Currently active nodes */
  activeNodes: number;

  /** Percentage of COGEN-powered nodes */
  cogenPercentage: number;

  /** Current inference requests per second */
  inferenceRps: number;

  /** P99 inference latency in ms */
  p99LatencyMs: number;

  /** Pending HPC jobs */
  pendingHpcJobs: number;

  /** Active HPC transfers */
  activeHpcTransfers: number;

  /** Network-wide average GPU utilization */
  avgGpuUtilization: number;

  /** Last update timestamp */
  lastUpdated: Date;
}

// =============================================================================
// Constants
// =============================================================================

/** ORNL (Oak Ridge National Laboratory) coordinates */
export const ORNL_POSITION: Position = [-84.2696, 35.9311];

/** Default globe view state */
export const DEFAULT_VIEW_STATE = {
  longitude: -98.5795,
  latitude: 39.8283,
  zoom: 3,
  pitch: 30,
  bearing: 0,
};
