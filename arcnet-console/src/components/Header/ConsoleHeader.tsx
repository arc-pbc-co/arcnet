/**
 * ConsoleHeader - Mission control header with stats and clock
 *
 * Displays:
 * - ASCII art logo and title
 * - Real-time global network statistics
 * - UTC clock with live updates
 * - Connection status indicator
 */

import { useState, useEffect, useMemo } from 'react';
import { useArcnetStore } from '@/stores/arcnetStore';
import { AsciiProgress } from '@/components/shared';
import { ConnectionStatus } from '@/components/ConnectionStatus';
import type { ConnectionState } from '@/hooks';
import styles from './ConsoleHeader.module.css';

// ASCII Art title - matches landing/login screens
const ASCII_LOGO = `    _    ____   ____ _   _      _
   / \\  |  _ \\ / ___| \\ | | ___| |_
  / _ \\ | |_) | |   |  \\| |/ _ \\ __|
 / ___ \\|  _ <| |___| |\\  |  __/ |_
/_/   \\_\\_| \\_\\\\____|_| \\_|\\___|\\__|`;

/**
 * Format number with K/M suffix
 */
function formatNumber(num: number): string {
  if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`;
  if (num >= 1000) return `${(num / 1000).toFixed(1)}K`;
  return num.toString();
}

/**
 * Real-time UTC clock hook
 */
function useUTCClock() {
  const [now, setNow] = useState(new Date());

  useEffect(() => {
    const interval = setInterval(() => {
      setNow(new Date());
    }, 1000);

    return () => clearInterval(interval);
  }, []);

  const time = now.toISOString().slice(11, 19); // HH:MM:SS
  const date = now.toISOString().slice(0, 10); // YYYY-MM-DD

  return { time, date };
}

export interface ConsoleHeaderProps {
  /** WebSocket connection state */
  connectionState?: ConnectionState;
  /** Whether using mock data */
  isMockMode?: boolean;
  /** Reconnect handler */
  onReconnect?: () => void;
}

export function ConsoleHeader({
  connectionState = 'disconnected',
  isMockMode = false,
  onReconnect
}: ConsoleHeaderProps) {
  const { time, date } = useUTCClock();

  // Connect to Zustand store
  const nodes = useArcnetStore((state) => state.nodes);
  const globalStats = useArcnetStore((state) => state.globalStats);

  // Compute derived stats with useMemo to avoid re-renders
  const { totalNodes, onlineCount, cogenPercentage } = useMemo(() => {
    const onlineNodes = nodes.filter((n) => n.status === 'online' || n.status === 'busy');
    const cogenNodes = nodes.filter((n) => n.energySource === 'cogen');
    return {
      totalNodes: nodes.length,
      onlineCount: onlineNodes.length,
      cogenPercentage: nodes.length > 0
        ? Math.round((cogenNodes.length / nodes.length) * 100)
        : 0,
    };
  }, [nodes]);

  // Use globalStats if available, otherwise compute from nodes
  const avgGpuUtil = globalStats?.avgGpuUtilization
    ?? (nodes.length > 0
        ? nodes.reduce((sum, n) => sum + n.gpuUtilization, 0) / nodes.length
        : 0);

  const inferenceRps = globalStats?.inferenceRps ?? 0;
  const p99Latency = globalStats?.p99LatencyMs ?? 0;

  // Determine stat colors based on thresholds
  const getLatencyClass = (ms: number) => {
    if (ms > 500) return styles.statError;
    if (ms > 200) return styles.statWarning;
    return styles.statHighlight;
  };

  const getUtilClass = (util: number) => {
    if (util > 0.9) return styles.statWarning;
    if (util > 0.7) return styles.statHighlight;
    return '';
  };

  return (
    <header className={styles.header}>
      {/* Left: Logo and Title */}
      <div className={styles.titleSection}>
        <pre className={styles.asciiTitle}>{ASCII_LOGO}</pre>
        <div className={styles.titleText}>
          <h1 className={styles.mainTitle}>ARCNet Console</h1>
          <span className={styles.subtitle}>Distributed AI Operations</span>
        </div>
      </div>

      {/* Center: Stats Bar */}
      <div className={styles.statsSection}>
        {/* Nodes Online */}
        <div className={styles.statItem}>
          <span className={styles.statLabel}>Nodes</span>
          <span className={`${styles.statValue} ${onlineCount > 0 ? styles.statHighlight : styles.statError}`}>
            {formatNumber(onlineCount)}/{formatNumber(totalNodes)}
          </span>
        </div>

        <div className={styles.statDivider} />

        {/* COGEN Percentage */}
        <div className={styles.statItem}>
          <span className={styles.statLabel}>COGEN</span>
          <div className={styles.statValue}>
            <AsciiProgress
              value={cogenPercentage}
              width={8}
              showPercentage={true}
              colorScheme="cogen"
              size="sm"
              brackets={false}
            />
          </div>
        </div>

        <div className={styles.statDivider} />

        {/* GPU Utilization */}
        <div className={styles.statItem}>
          <span className={styles.statLabel}>GPU Util</span>
          <div className={`${styles.statValue} ${getUtilClass(avgGpuUtil)}`}>
            <AsciiProgress
              value={avgGpuUtil * 100}
              width={8}
              showPercentage={true}
              colorScheme="auto"
              size="sm"
              brackets={false}
            />
          </div>
        </div>

        <div className={styles.statDivider} />

        {/* Requests per Second */}
        <div className={styles.statItem}>
          <span className={styles.statLabel}>Req/s</span>
          <span className={`${styles.statValue} ${styles.statHighlight}`}>
            {formatNumber(inferenceRps)}
          </span>
        </div>

        <div className={styles.statDivider} />

        {/* P99 Latency */}
        <div className={styles.statItem}>
          <span className={styles.statLabel}>P99 Latency</span>
          <span className={`${styles.statValue} ${getLatencyClass(p99Latency)}`}>
            {p99Latency > 0 ? `${p99Latency}ms` : '—'}
          </span>
        </div>
      </div>

      {/* Right: Clock and Connection Status */}
      <div className={styles.rightSection}>
        {/* CLI Demo Link */}
        {window.location.hash !== '#cli-demo' && (
          <a
            href="#cli-demo"
            className={styles.demoLink}
            title="Open CLI Demo"
          >
            ⌨️ CLI Demo
          </a>
        )}
        {window.location.hash === '#cli-demo' && (
          <a
            href="#"
            className={styles.demoLink}
            title="Back to Console"
          >
            ← Back to Console
          </a>
        )}

        {/* Connection Status */}
        <ConnectionStatus
          state={connectionState}
          isMockMode={isMockMode}
          onClick={onReconnect}
        />

        {/* UTC Clock */}
        <div className={styles.clock}>
          <span className={styles.clockTime}>{time}</span>
          <span className={styles.clockDate}>{date}</span>
          <span className={styles.clockLabel}>UTC</span>
        </div>
      </div>
    </header>
  );
}

export default ConsoleHeader;
