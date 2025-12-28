/**
 * AsciiProgress Component - Terminal-style progress bar
 *
 * Renders progress bars using ASCII characters like [████████░░░░] 67%
 * Supports various character styles and automatic color coding based on value.
 */

import { useMemo } from 'react';
import styles from './AsciiProgress.module.css';

export type ProgressColorScheme = 'auto' | 'cyan' | 'hpc' | 'cogen' | 'green';
export type ProgressSize = 'sm' | 'md' | 'lg';
export type ProgressCharStyle = 'blocks' | 'hashes' | 'dots' | 'arrows' | 'equals';

export interface AsciiProgressProps {
  /** Progress value (0-100 or 0-1) */
  value: number;
  /** Maximum value (default 100, use 1 for decimal percentages) */
  max?: number;
  /** Width in characters */
  width?: number;
  /** Show percentage label */
  showPercentage?: boolean;
  /** Optional text label */
  label?: string;
  /** Color scheme */
  colorScheme?: ProgressColorScheme;
  /** Size variant */
  size?: ProgressSize;
  /** Character style */
  charStyle?: ProgressCharStyle;
  /** Show brackets around bar */
  brackets?: boolean;
  /** Animate the progress bar */
  animated?: boolean;
  /** Additional CSS class */
  className?: string;
}

// Character sets for different styles
const CHAR_STYLES: Record<ProgressCharStyle, { filled: string; empty: string }> = {
  blocks: { filled: '█', empty: '░' },
  hashes: { filled: '#', empty: '-' },
  dots: { filled: '●', empty: '○' },
  arrows: { filled: '▶', empty: '▷' },
  equals: { filled: '=', empty: '-' },
};

// Thresholds for auto color
const LOW_THRESHOLD = 25;
const MEDIUM_THRESHOLD = 60;

function getAutoColorClass(percentage: number): string {
  if (percentage < LOW_THRESHOLD) return styles.colorLow;
  if (percentage < MEDIUM_THRESHOLD) return styles.colorMedium;
  return styles.colorHigh;
}

function getColorClass(scheme: ProgressColorScheme, percentage: number): string {
  switch (scheme) {
    case 'auto':
      return getAutoColorClass(percentage);
    case 'cyan':
      return styles.colorCyan;
    case 'hpc':
      return styles.colorHpc;
    case 'cogen':
      return styles.colorCogen;
    case 'green':
      return styles.colorHigh;
    default:
      return styles.colorHigh;
  }
}

const sizeClasses: Record<ProgressSize, string> = {
  sm: styles.sizeSm,
  md: styles.sizeMd,
  lg: styles.sizeLg,
};

export function AsciiProgress({
  value,
  max = 100,
  width = 12,
  showPercentage = true,
  label,
  colorScheme = 'auto',
  size = 'md',
  charStyle = 'blocks',
  brackets = true,
  animated = false,
  className = '',
}: AsciiProgressProps) {
  const { bar, percentage } = useMemo(() => {
    // Normalize to percentage
    const pct = Math.max(0, Math.min(100, (value / max) * 100));
    const filledCount = Math.round((pct / 100) * width);
    const emptyCount = width - filledCount;

    const chars = CHAR_STYLES[charStyle];
    const filledPart = chars.filled.repeat(filledCount);
    const emptyPart = chars.empty.repeat(emptyCount);

    let barStr = filledPart + emptyPart;
    if (brackets) {
      barStr = '[' + barStr + ']';
    }

    return {
      bar: barStr,
      percentage: pct,
    };
  }, [value, max, width, charStyle, brackets]);

  const colorClass = getColorClass(colorScheme, percentage);

  const containerClasses = [
    styles.container,
    sizeClasses[size],
    colorClass,
    animated && styles.animated,
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={containerClasses}>
      <span className={styles.bar}>{bar}</span>
      {showPercentage && (
        <span className={styles.percentage}>{Math.round(percentage)}%</span>
      )}
      {label && <span className={styles.label}>{label}</span>}
    </div>
  );
}

/**
 * Simplified progress bar for inline use
 */
export function InlineProgress({
  value,
  max = 100,
  width = 8,
}: {
  value: number;
  max?: number;
  width?: number;
}) {
  const percentage = Math.max(0, Math.min(100, (value / max) * 100));
  const filledCount = Math.round((percentage / 100) * width);
  const emptyCount = width - filledCount;

  return (
    <span className={styles.bar}>
      {'█'.repeat(filledCount)}
      {'░'.repeat(emptyCount)}
    </span>
  );
}

export default AsciiProgress;
