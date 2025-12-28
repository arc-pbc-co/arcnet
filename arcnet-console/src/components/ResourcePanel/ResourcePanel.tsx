/**
 * ResourcePanel - System metrics display with ASCII progress bars
 *
 * Shows network-wide resource utilization:
 * - GPU utilization across network
 * - Memory usage
 * - Requests per second
 * - Queue depth
 * - COGEN coverage percentage
 */

import { useMemo } from 'react';
import { useArcnetStore } from '@/stores/arcnetStore';
import { Panel, AsciiProgress, StatusBadge } from '@/components/shared';
import styles from './ResourcePanel.module.css';

/**
 * Format number with K/M suffix
 */
function formatNumber(num: number): string {
  if (num >= 1e6) return `${(num / 1e6).toFixed(1)}M`;
  if (num >= 1e3) return `${(num / 1e3).toFixed(1)}K`;
  return num.toFixed(0);
}

export function ResourcePanel() {
  // Connect to Zustand store
  const nodes = useArcnetStore((state) => state.nodes);
  const inferenceArcs = useArcnetStore((state) => state.inferenceArcs);
  const hpcTransfers = useArcnetStore((state) => state.hpcTransfers);
  const globalStats = useArcnetStore((state) => state.globalStats);

  // Compute metrics from nodes
  const metrics = useMemo(() => {
    if (nodes.length === 0) {
      return {
        avgGpuUtil: 0,
        totalGpuMemoryGb: 0,
        usedGpuMemoryGb: 0,
        memoryUsagePercent: 0,
        totalGpuCount: 0,
        activeGpuCount: 0,
        cogenNodes: 0,
        gridNodes: 0,
        batteryNodes: 0,
        cogenPercentage: 0,
        onlineNodes: 0,
        busyNodes: 0,
        avgBattery: 0,
      };
    }

    const onlineNodes = nodes.filter(
      (n) => n.status === 'online' || n.status === 'busy'
    );
    const busyNodes = nodes.filter((n) => n.status === 'busy');

    // GPU utilization
    const avgGpuUtil =
      onlineNodes.length > 0
        ? onlineNodes.reduce((sum, n) => sum + n.gpuUtilization, 0) /
          onlineNodes.length
        : 0;

    // Memory calculations
    const totalGpuMemoryGb = nodes.reduce(
      (sum, n) => sum + n.gpuMemoryTotalGb * n.gpuCount,
      0
    );
    const freeGpuMemoryGb = onlineNodes.reduce(
      (sum, n) => sum + n.gpuMemoryFreeGb * n.gpuCount,
      0
    );
    const usedGpuMemoryGb = totalGpuMemoryGb - freeGpuMemoryGb;
    const memoryUsagePercent =
      totalGpuMemoryGb > 0 ? (usedGpuMemoryGb / totalGpuMemoryGb) * 100 : 0;

    // GPU counts
    const totalGpuCount = nodes.reduce((sum, n) => sum + n.gpuCount, 0);
    const activeGpuCount = onlineNodes.reduce((sum, n) => sum + n.gpuCount, 0);

    // Energy breakdown
    const cogenNodes = nodes.filter((n) => n.energySource === 'cogen').length;
    const gridNodes = nodes.filter((n) => n.energySource === 'grid').length;
    const batteryNodes = nodes.filter(
      (n) => n.energySource === 'battery'
    ).length;
    const cogenPercentage =
      nodes.length > 0 ? (cogenNodes / nodes.length) * 100 : 0;

    // Average battery
    const avgBattery =
      nodes.length > 0
        ? nodes.reduce((sum, n) => sum + n.batteryLevel, 0) / nodes.length
        : 0;

    return {
      avgGpuUtil,
      totalGpuMemoryGb,
      usedGpuMemoryGb,
      memoryUsagePercent,
      totalGpuCount,
      activeGpuCount,
      cogenNodes,
      gridNodes,
      batteryNodes,
      cogenPercentage,
      onlineNodes: onlineNodes.length,
      busyNodes: busyNodes.length,
      avgBattery,
    };
  }, [nodes]);

  // Queue depth (pending inference requests)
  const queueDepth = inferenceArcs.filter(
    (a) => a.status === 'dispatched'
  ).length;

  // Active HPC transfers
  const activeHpc = hpcTransfers.filter(
    (t) => t.status === 'transferring' || t.status === 'pending'
  ).length;

  // Requests per second from globalStats or estimate from arcs
  const rps = globalStats?.inferenceRps ?? inferenceArcs.length * 2;

  return (
    <Panel title="System Resources" variant="default" size="sm">
      <div className={styles.metricsGrid}>
        {/* GPU Utilization */}
        <div className={styles.metricRow}>
          <div className={styles.metricHeader}>
            <span className={styles.metricLabel}>GPU Util</span>
            <span className={styles.metricValue}>
              {Math.round(metrics.avgGpuUtil * 100)}%
            </span>
          </div>
          <AsciiProgress
            value={metrics.avgGpuUtil * 100}
            width={16}
            showPercentage={false}
            colorScheme="auto"
            size="sm"
            brackets={true}
          />
        </div>

        {/* VRAM Usage */}
        <div className={styles.metricRow}>
          <div className={styles.metricHeader}>
            <span className={styles.metricLabel}>VRAM</span>
            <span className={styles.metricValue}>
              {metrics.usedGpuMemoryGb.toFixed(0)}/
              {metrics.totalGpuMemoryGb.toFixed(0)}GB
            </span>
          </div>
          <AsciiProgress
            value={metrics.memoryUsagePercent}
            width={16}
            showPercentage={false}
            colorScheme="auto"
            size="sm"
            brackets={true}
          />
        </div>

        {/* Request Rate */}
        <div className={styles.metricRow}>
          <div className={styles.metricHeader}>
            <span className={styles.metricLabel}>Req/s</span>
            <span className={`${styles.metricValue} ${styles.highlight}`}>
              {formatNumber(rps)}
            </span>
          </div>
          <div className={styles.metricBar}>
            <span className={styles.metricSubtext}>
              {inferenceArcs.length} active
            </span>
          </div>
        </div>

        {/* Queue Depth */}
        <div className={styles.metricRow}>
          <div className={styles.metricHeader}>
            <span className={styles.metricLabel}>Queue</span>
            <span
              className={`${styles.metricValue} ${queueDepth > 10 ? styles.warning : ''}`}
            >
              {queueDepth}
            </span>
          </div>
          <AsciiProgress
            value={Math.min(queueDepth, 50)}
            max={50}
            width={16}
            showPercentage={false}
            colorScheme={queueDepth > 20 ? 'auto' : 'cyan'}
            size="sm"
            brackets={true}
          />
        </div>

        {/* COGEN Coverage */}
        <div className={styles.metricRow}>
          <div className={styles.metricHeader}>
            <span className={styles.metricLabel}>COGEN</span>
            <span className={`${styles.metricValue} ${styles.cogen}`}>
              {Math.round(metrics.cogenPercentage)}%
            </span>
          </div>
          <AsciiProgress
            value={metrics.cogenPercentage}
            width={16}
            showPercentage={false}
            colorScheme="cogen"
            size="sm"
            brackets={true}
          />
        </div>

        {/* Divider */}
        <div className={styles.divider} />

        {/* Summary Stats */}
        <div className={styles.summaryGrid}>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>GPUs</span>
            <span className={styles.summaryValue}>
              {metrics.activeGpuCount}/{metrics.totalGpuCount}
            </span>
          </div>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>Nodes</span>
            <span className={styles.summaryValue}>
              {metrics.onlineNodes}/{nodes.length}
            </span>
          </div>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>HPC</span>
            <span className={`${styles.summaryValue} ${styles.hpc}`}>
              {activeHpc}
            </span>
          </div>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>Battery</span>
            <span className={styles.summaryValue}>
              {Math.round(metrics.avgBattery * 100)}%
            </span>
          </div>
        </div>

        {/* Energy Breakdown */}
        <div className={styles.energyBreakdown}>
          <span className={styles.energyTitle}>Energy Mix</span>
          <div className={styles.energyRow}>
            <StatusBadge status="cogen" size="sm" dotOnly />
            <span className={styles.energyCount}>{metrics.cogenNodes}</span>
            <StatusBadge status="grid" size="sm" dotOnly />
            <span className={styles.energyCount}>{metrics.gridNodes}</span>
            <StatusBadge status="battery" size="sm" dotOnly />
            <span className={styles.energyCount}>{metrics.batteryNodes}</span>
          </div>
        </div>
      </div>
    </Panel>
  );
}

export default ResourcePanel;
