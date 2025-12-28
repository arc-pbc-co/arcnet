/**
 * ConnectionStatus - WebSocket connection indicator
 * 
 * Shows real-time connection status with visual feedback:
 * - Connected: Green pulse
 * - Connecting: Yellow pulse
 * - Disconnected: Red
 * - Mock Mode: Blue
 */

import React from 'react';
import styles from './ConnectionStatus.module.css';
import type { ConnectionState } from '@/hooks';

export interface ConnectionStatusProps {
  /** Connection state */
  state: ConnectionState;
  /** Whether using mock data */
  isMockMode: boolean;
  /** Optional click handler for reconnect */
  onClick?: () => void;
}

export function ConnectionStatus({ state, isMockMode, onClick }: ConnectionStatusProps) {
  const getStatusInfo = () => {
    if (isMockMode) {
      return {
        label: 'MOCK',
        color: 'blue',
        pulse: false,
        tooltip: 'Using simulated telemetry data',
      };
    }

    switch (state) {
      case 'connected':
        return {
          label: 'LIVE',
          color: 'green',
          pulse: true,
          tooltip: 'Connected to ArcNet telemetry stream',
        };
      case 'connecting':
        return {
          label: 'CONNECTING',
          color: 'yellow',
          pulse: true,
          tooltip: 'Connecting to telemetry stream...',
        };
      case 'error':
        return {
          label: 'ERROR',
          color: 'red',
          pulse: false,
          tooltip: 'Connection error - click to retry',
        };
      case 'disconnected':
      default:
        return {
          label: 'OFFLINE',
          color: 'red',
          pulse: false,
          tooltip: 'Disconnected - click to reconnect',
        };
    }
  };

  const status = getStatusInfo();
  const isClickable = !isMockMode && (state === 'disconnected' || state === 'error');

  return (
    <div
      className={`${styles.container} ${isClickable ? styles.clickable : ''}`}
      onClick={isClickable ? onClick : undefined}
      title={status.tooltip}
    >
      <div className={`${styles.indicator} ${styles[status.color]} ${status.pulse ? styles.pulse : ''}`} />
      <span className={styles.label}>{status.label}</span>
    </div>
  );
}

