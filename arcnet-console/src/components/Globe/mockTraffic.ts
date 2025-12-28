/**
 * Mock traffic generator for testing arc visualizations
 *
 * Generates inference arcs and HPC transfers at regular intervals.
 */

import type { InferenceArc, HpcTransfer, Priority, InferenceStatus, HpcJobStatus } from '@/types/arcnet';
import type { Node } from '@/types/arcnet';

// ORNL coordinates
export const ORNL_POSITION: [number, number] = [-84.2696, 35.9311];

// US geographic bounds for random source positions
const US_BOUNDS = {
  minLng: -124.7,
  maxLng: -66.9,
  minLat: 25.1,
  maxLat: 49.4,
};

/**
 * Generate a random position within the US
 */
function randomUSPosition(): [number, number] {
  const lng = US_BOUNDS.minLng + Math.random() * (US_BOUNDS.maxLng - US_BOUNDS.minLng);
  const lat = US_BOUNDS.minLat + Math.random() * (US_BOUNDS.maxLat - US_BOUNDS.minLat);
  return [lng, lat];
}

/**
 * Generate a mock inference arc
 */
export function generateInferenceArc(nodes: Node[]): InferenceArc | null {
  if (nodes.length === 0) return null;

  // Pick a random online node as target
  const onlineNodes = nodes.filter(n => n.status === 'online' || n.status === 'busy');
  if (onlineNodes.length === 0) return null;

  const targetNode = onlineNodes[Math.floor(Math.random() * onlineNodes.length)];

  // Random source position (simulating client request origin)
  const source = randomUSPosition();

  // Random priority with weighted distribution
  const priorityRoll = Math.random();
  let priority: Priority;
  if (priorityRoll < 0.05) {
    priority = 'critical';
  } else if (priorityRoll < 0.3) {
    priority = 'normal';
  } else {
    priority = 'background';
  }

  // Pick a random model from the target node's loaded models
  const modelId = targetNode.modelsLoaded.length > 0
    ? targetNode.modelsLoaded[Math.floor(Math.random() * targetNode.modelsLoaded.length)]
    : 'llama-3.1-8b';

  const arc: InferenceArc = {
    id: `inf-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    source,
    target: targetNode.position,
    targetNodeId: targetNode.id,
    modelId,
    priority,
    status: 'dispatched',
    timestamp: new Date(),
    progress: 0,
  };

  return arc;
}

/**
 * Generate a mock HPC transfer
 */
export function generateHpcTransfer(nodes: Node[]): HpcTransfer | null {
  if (nodes.length === 0) return null;

  // Pick a random node as source
  const onlineNodes = nodes.filter(n => n.status === 'online' || n.status === 'busy');
  if (onlineNodes.length === 0) return null;

  const sourceNode = onlineNodes[Math.floor(Math.random() * onlineNodes.length)];

  // Random dataset size (100GB - 2TB)
  const datasetSizeGb = 100 + Math.random() * 1900;

  const transfer: HpcTransfer = {
    id: `hpc-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    sourceNodeId: sourceNode.id,
    source: sourceNode.position,
    target: ORNL_POSITION,
    datasetSizeGb,
    status: 'transferring',
    progress: 0,
    bytesTransferred: 0,
    timestamp: new Date(),
  };

  return transfer;
}

/**
 * Update inference arc status over time
 */
export function updateInferenceArc(arc: InferenceArc, elapsedMs: number): InferenceArc {
  const totalDurationMs = 3000; // 3 seconds for full animation
  const newProgress = Math.min(1, elapsedMs / totalDurationMs);

  let status: InferenceStatus = arc.status;
  if (newProgress >= 1) {
    // 95% success rate
    status = Math.random() < 0.95 ? 'completed' : 'failed';
  } else if (newProgress > 0.8) {
    status = 'processing';
  }

  return {
    ...arc,
    progress: newProgress,
    status,
    latencyMs: status === 'completed' ? Math.round(50 + Math.random() * 200) : undefined,
  };
}

/**
 * Update HPC transfer progress over time
 */
export function updateHpcTransfer(transfer: HpcTransfer, elapsedMs: number): HpcTransfer {
  // Simulate transfer speed based on dataset size
  // Larger transfers take longer (1TB = ~30 seconds for demo)
  const totalDurationMs = (transfer.datasetSizeGb / 1000) * 30000;
  const newProgress = Math.min(1, elapsedMs / totalDurationMs);

  let status: HpcJobStatus = transfer.status;
  if (newProgress >= 1) {
    status = Math.random() < 0.98 ? 'completed' : 'failed';
  } else if (newProgress > 0.9) {
    status = 'queued'; // Queued at ORNL
  }

  return {
    ...transfer,
    progress: newProgress,
    status,
    bytesTransferred: Math.round(transfer.datasetSizeGb * 1024 * 1024 * 1024 * newProgress),
  };
}

/**
 * Traffic generator class for continuous mock traffic
 */
export class MockTrafficGenerator {
  private inferenceInterval: ReturnType<typeof setInterval> | null = null;
  private hpcInterval: ReturnType<typeof setInterval> | null = null;
  private updateInterval: ReturnType<typeof setInterval> | null = null;
  private arcTimestamps: Map<string, number> = new Map();

  private getNodes: () => Node[];
  private addInferenceArc: (arc: InferenceArc) => void;
  private removeInferenceArc: (id: string) => void;
  private addHpcTransfer: (transfer: HpcTransfer) => void;
  private updateHpcTransferFn: (id: string, updates: Partial<HpcTransfer>) => void;
  private removeHpcTransfer: (id: string) => void;
  private getInferenceArcs: () => InferenceArc[];
  private getHpcTransfers: () => HpcTransfer[];

  constructor(
    getNodes: () => Node[],
    addInferenceArc: (arc: InferenceArc) => void,
    removeInferenceArc: (id: string) => void,
    addHpcTransfer: (transfer: HpcTransfer) => void,
    updateHpcTransferFn: (id: string, updates: Partial<HpcTransfer>) => void,
    removeHpcTransfer: (id: string) => void,
    getInferenceArcs: () => InferenceArc[],
    getHpcTransfers: () => HpcTransfer[],
  ) {
    this.getNodes = getNodes;
    this.addInferenceArc = addInferenceArc;
    this.removeInferenceArc = removeInferenceArc;
    this.addHpcTransfer = addHpcTransfer;
    this.updateHpcTransferFn = updateHpcTransferFn;
    this.removeHpcTransfer = removeHpcTransfer;
    this.getInferenceArcs = getInferenceArcs;
    this.getHpcTransfers = getHpcTransfers;
  }

  start(inferenceIntervalMs: number = 500, hpcIntervalMs: number = 5000) {
    // Generate inference arcs
    this.inferenceInterval = setInterval(() => {
      const nodes = this.getNodes();
      const arc = generateInferenceArc(nodes);
      if (arc) {
        this.addInferenceArc(arc);
        this.arcTimestamps.set(arc.id, Date.now());
      }
    }, inferenceIntervalMs);

    // Generate HPC transfers (less frequent)
    this.hpcInterval = setInterval(() => {
      const nodes = this.getNodes();
      // Only generate if we have fewer than 5 active transfers
      const activeTransfers = this.getHpcTransfers().filter(
        t => t.status === 'transferring' || t.status === 'pending'
      );
      if (activeTransfers.length < 5) {
        const transfer = generateHpcTransfer(nodes);
        if (transfer) {
          this.addHpcTransfer(transfer);
          this.arcTimestamps.set(transfer.id, Date.now());
        }
      }
    }, hpcIntervalMs);

    // Update and cleanup arcs
    this.updateInterval = setInterval(() => {
      const now = Date.now();

      // Update and remove completed inference arcs
      const inferenceArcs = this.getInferenceArcs();
      for (const arc of inferenceArcs) {
        const startTime = this.arcTimestamps.get(arc.id) || now;
        const elapsed = now - startTime;

        // Remove arcs older than 3 seconds
        if (elapsed > 3000) {
          this.removeInferenceArc(arc.id);
          this.arcTimestamps.delete(arc.id);
        }
      }

      // Update HPC transfers
      const hpcTransfers = this.getHpcTransfers();
      for (const transfer of hpcTransfers) {
        const startTime = this.arcTimestamps.get(transfer.id) || now;
        const elapsed = now - startTime;

        const updated = updateHpcTransfer(transfer, elapsed);
        if (updated.status === 'completed' || updated.status === 'failed') {
          // Keep completed transfers visible for 2 seconds, then remove
          if (elapsed > (transfer.datasetSizeGb / 1000) * 30000 + 2000) {
            this.removeHpcTransfer(transfer.id);
            this.arcTimestamps.delete(transfer.id);
          } else {
            this.updateHpcTransferFn(transfer.id, {
              progress: updated.progress,
              status: updated.status,
              bytesTransferred: updated.bytesTransferred,
            });
          }
        } else {
          this.updateHpcTransferFn(transfer.id, {
            progress: updated.progress,
            status: updated.status,
            bytesTransferred: updated.bytesTransferred,
          });
        }
      }
    }, 100);
  }

  stop() {
    if (this.inferenceInterval) {
      clearInterval(this.inferenceInterval);
      this.inferenceInterval = null;
    }
    if (this.hpcInterval) {
      clearInterval(this.hpcInterval);
      this.hpcInterval = null;
    }
    if (this.updateInterval) {
      clearInterval(this.updateInterval);
      this.updateInterval = null;
    }
    this.arcTimestamps.clear();
  }
}
