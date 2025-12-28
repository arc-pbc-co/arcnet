/**
 * NodeDetail - Selected node information panel
 *
 * Displays detailed information about the currently selected node:
 * - Node ID and name
 * - Status badge
 * - Energy source and battery level
 * - GPU utilization and memory
 * - Loaded models
 * - Geographic location
 *
 * Shows a hint when no node is selected.
 */

import { useMemo } from 'react';
import { useArcnetStore } from '@/stores/arcnetStore';
import { Panel, AsciiProgress, StatusBadge } from '@/components/shared';
import type { Node } from '@/types/arcnet';
import styles from './NodeDetail.module.css';

/**
 * Format a timestamp to relative time
 */
function formatLastSeen(date: Date): string {
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffSec = Math.floor(diffMs / 1000);

  if (diffSec < 60) return `${diffSec}s ago`;
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}

/**
 * Get status type for StatusBadge
 */
function getStatusType(status: Node['status']): 'online' | 'offline' | 'busy' | 'idle' | 'stale' {
  return status;
}

/**
 * Get energy source type for StatusBadge
 */
function getEnergyType(source: Node['energySource']): 'cogen' | 'grid' | 'battery' {
  return source;
}

/**
 * Empty state component
 */
function EmptyState() {
  return (
    <div className={styles.emptyState}>
      <div className={styles.emptyIcon}>[ ]</div>
      <span className={styles.emptyTitle}>No Node Selected</span>
      <span className={styles.emptyHint}>
        Click a node on the globe to view details
      </span>
      <div className={styles.emptyKeys}>
        <span className={styles.keyHint}>
          <kbd>Click</kbd> Select node
        </span>
        <span className={styles.keyHint}>
          <kbd>Esc</kbd> Deselect
        </span>
      </div>
    </div>
  );
}

export function NodeDetail() {
  // Connect to Zustand store
  const selectedNodeId = useArcnetStore((state) => state.selectedNodeId);
  const nodes = useArcnetStore((state) => state.nodes);
  const flyToNode = useArcnetStore((state) => state.flyToNode);
  const setSelectedNode = useArcnetStore((state) => state.setSelectedNode);

  // Find selected node
  const selectedNode = useMemo(() => {
    if (!selectedNodeId) return null;
    return nodes.find((n) => n.id === selectedNodeId) ?? null;
  }, [selectedNodeId, nodes]);

  // Handle close button
  const handleClose = () => {
    setSelectedNode(null);
  };

  // Handle fly to node
  const handleFlyTo = () => {
    if (selectedNodeId) {
      flyToNode(selectedNodeId);
    }
  };

  // Calculate memory usage
  const memoryUsage = useMemo(() => {
    if (!selectedNode) return { used: 0, total: 0, percent: 0 };
    const total = selectedNode.gpuMemoryTotalGb * selectedNode.gpuCount;
    const free = selectedNode.gpuMemoryFreeGb * selectedNode.gpuCount;
    const used = total - free;
    return {
      used,
      total,
      percent: total > 0 ? (used / total) * 100 : 0,
    };
  }, [selectedNode]);

  if (!selectedNode) {
    return (
      <Panel title="Node Detail" variant="default" size="sm">
        <EmptyState />
      </Panel>
    );
  }

  return (
    <Panel
      title="Node Detail"
      variant="accent"
      size="sm"
      headerActions={
        <button className={styles.closeButton} onClick={handleClose}>
          [×]
        </button>
      }
    >
      <div className={styles.detailContent}>
        {/* Node Identity */}
        <div className={styles.section}>
          <div className={styles.nodeHeader}>
            <span className={styles.nodeName}>{selectedNode.name}</span>
            <StatusBadge status={getStatusType(selectedNode.status)} size="sm" />
          </div>
          <span className={styles.nodeId}>{selectedNode.id}</span>
        </div>

        {/* Divider */}
        <div className={styles.divider} />

        {/* Energy & Battery */}
        <div className={styles.section}>
          <div className={styles.row}>
            <span className={styles.label}>Energy</span>
            <StatusBadge status={getEnergyType(selectedNode.energySource)} size="sm" />
          </div>
          <div className={styles.row}>
            <span className={styles.label}>Battery</span>
            <div className={styles.progressCell}>
              <AsciiProgress
                value={selectedNode.batteryLevel * 100}
                width={10}
                showPercentage={true}
                colorScheme="auto"
                size="sm"
                brackets={false}
              />
            </div>
          </div>
        </div>

        {/* Divider */}
        <div className={styles.divider} />

        {/* GPU Stats */}
        <div className={styles.section}>
          <div className={styles.sectionTitle}>GPU</div>
          <div className={styles.row}>
            <span className={styles.label}>Count</span>
            <span className={styles.value}>{selectedNode.gpuCount}x</span>
          </div>
          <div className={styles.row}>
            <span className={styles.label}>Util</span>
            <div className={styles.progressCell}>
              <AsciiProgress
                value={selectedNode.gpuUtilization * 100}
                width={10}
                showPercentage={true}
                colorScheme="auto"
                size="sm"
                brackets={false}
              />
            </div>
          </div>
          <div className={styles.row}>
            <span className={styles.label}>VRAM</span>
            <span className={styles.value}>
              {memoryUsage.used.toFixed(1)}/{memoryUsage.total.toFixed(0)}GB
            </span>
          </div>
        </div>

        {/* Divider */}
        <div className={styles.divider} />

        {/* Models */}
        <div className={styles.section}>
          <div className={styles.sectionTitle}>
            Models Loaded ({selectedNode.modelsLoaded.length})
          </div>
          <div className={styles.modelList}>
            {selectedNode.modelsLoaded.length > 0 ? (
              selectedNode.modelsLoaded.map((model) => (
                <span key={model} className={styles.modelTag}>
                  {model}
                </span>
              ))
            ) : (
              <span className={styles.noModels}>No models loaded</span>
            )}
          </div>
        </div>

        {/* Divider */}
        <div className={styles.divider} />

        {/* Location */}
        <div className={styles.section}>
          <div className={styles.sectionTitle}>Location</div>
          <div className={styles.row}>
            <span className={styles.label}>Geozone</span>
            <span className={styles.value}>{selectedNode.geozone}</span>
          </div>
          <div className={styles.row}>
            <span className={styles.label}>Coords</span>
            <span className={styles.valueSmall}>
              {selectedNode.position[1].toFixed(4)}°,{' '}
              {selectedNode.position[0].toFixed(4)}°
            </span>
          </div>
          <div className={styles.row}>
            <span className={styles.label}>Geohash</span>
            <span className={styles.valueCode}>{selectedNode.geohash}</span>
          </div>
        </div>

        {/* Divider */}
        <div className={styles.divider} />

        {/* Metadata */}
        <div className={styles.section}>
          <div className={styles.row}>
            <span className={styles.label}>Last Seen</span>
            <span className={styles.valueSmall}>
              {formatLastSeen(selectedNode.lastSeen)}
            </span>
          </div>
          {selectedNode.reservation && (
            <div className={styles.row}>
              <span className={styles.label}>Reserved</span>
              <span className={styles.valueSmall}>
                {selectedNode.reservation.requestId.slice(0, 8)}...
              </span>
            </div>
          )}
        </div>

        {/* Actions */}
        <div className={styles.actions}>
          <button className={styles.actionButton} onClick={handleFlyTo}>
            [Fly To]
          </button>
        </div>
      </div>
    </Panel>
  );
}

export default NodeDetail;
