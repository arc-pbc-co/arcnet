/**
 * StatusBadge Component - Colored status indicator with label
 *
 * Displays a glowing dot with optional label for various status types.
 * Supports node status, energy sources, and transfer states.
 */

import styles from './StatusBadge.module.css';

export type StatusType =
  // Node status
  | 'online'
  | 'offline'
  | 'busy'
  | 'idle'
  | 'stale'
  // Alert status
  | 'warning'
  | 'error'
  // Energy sources
  | 'cogen'
  | 'grid'
  | 'battery'
  // Transfer status
  | 'pending'
  | 'active'
  | 'completed'
  | 'failed'
  // Accent
  | 'hpc'
  | 'cyan';

export type BadgeSize = 'sm' | 'md' | 'lg';
export type BadgeVariant = 'default' | 'pill' | 'minimal';

export interface StatusBadgeProps {
  /** Status type determines color and animation */
  status: StatusType;
  /** Optional label text (defaults to capitalized status) */
  label?: string;
  /** Size variant */
  size?: BadgeSize;
  /** Visual variant */
  variant?: BadgeVariant;
  /** Hide the label, show only dot */
  dotOnly?: boolean;
  /** Additional CSS class */
  className?: string;
}

const statusClasses: Record<StatusType, string> = {
  online: styles.statusOnline,
  offline: styles.statusOffline,
  busy: styles.statusBusy,
  idle: styles.statusIdle,
  stale: styles.statusStale,
  warning: styles.statusWarning,
  error: styles.statusError,
  cogen: styles.statusCogen,
  grid: styles.statusGrid,
  battery: styles.statusBattery,
  pending: styles.statusPending,
  active: styles.statusActive,
  completed: styles.statusCompleted,
  failed: styles.statusFailed,
  hpc: styles.statusHpc,
  cyan: styles.statusCyan,
};

const sizeClasses: Record<BadgeSize, string> = {
  sm: styles.sizeSm,
  md: styles.sizeMd,
  lg: styles.sizeLg,
};

const variantClasses: Record<BadgeVariant, string> = {
  default: '',
  pill: styles.variantPill,
  minimal: styles.variantMinimal,
};

// Default labels for status types
const defaultLabels: Record<StatusType, string> = {
  online: 'Online',
  offline: 'Offline',
  busy: 'Busy',
  idle: 'Idle',
  stale: 'Stale',
  warning: 'Warning',
  error: 'Error',
  cogen: 'COGEN',
  grid: 'Grid',
  battery: 'Battery',
  pending: 'Pending',
  active: 'Active',
  completed: 'Completed',
  failed: 'Failed',
  hpc: 'HPC',
  cyan: 'Active',
};

export function StatusBadge({
  status,
  label,
  size = 'md',
  variant = 'default',
  dotOnly = false,
  className = '',
}: StatusBadgeProps) {
  const displayLabel = label ?? defaultLabels[status];

  const badgeClasses = [
    styles.badge,
    statusClasses[status],
    sizeClasses[size],
    variantClasses[variant],
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <span className={badgeClasses}>
      <span className={styles.dot} />
      {!dotOnly && <span className={styles.label}>{displayLabel}</span>}
    </span>
  );
}

/**
 * Convenience component for node status
 */
export function NodeStatusBadge({
  status,
  size = 'sm',
}: {
  status: 'online' | 'offline' | 'busy' | 'idle' | 'stale';
  size?: BadgeSize;
}) {
  return <StatusBadge status={status} size={size} />;
}

/**
 * Convenience component for energy source
 */
export function EnergyBadge({
  source,
  size = 'sm',
}: {
  source: 'cogen' | 'grid' | 'battery';
  size?: BadgeSize;
}) {
  return <StatusBadge status={source} size={size} />;
}

/**
 * Convenience component for transfer status
 */
export function TransferStatusBadge({
  status,
  size = 'sm',
}: {
  status: 'pending' | 'active' | 'completed' | 'failed';
  size?: BadgeSize;
}) {
  return <StatusBadge status={status} size={size} />;
}

export default StatusBadge;
