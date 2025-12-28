/**
 * Panel Component - Terminal-styled container with optional header
 *
 * A flexible container component that provides the terminal aesthetic
 * with optional title, corner decorations, and variant styles.
 */

import type { ReactNode } from 'react';
import styles from './Panel.module.css';

export type PanelVariant = 'default' | 'accent' | 'warning' | 'error' | 'hpc';
export type PanelSize = 'sm' | 'md' | 'lg';

export interface PanelProps {
  /** Panel content */
  children: ReactNode;
  /** Optional title displayed in header */
  title?: string;
  /** Optional actions displayed in header (right side) */
  headerActions?: ReactNode;
  /** Visual variant */
  variant?: PanelVariant;
  /** Padding size */
  size?: PanelSize;
  /** Show corner decorations */
  corners?: boolean;
  /** Add glow text effect to title */
  glowTitle?: boolean;
  /** Make panel interactive (hover effects) */
  interactive?: boolean;
  /** Additional CSS classes */
  className?: string;
  /** Click handler for interactive panels */
  onClick?: () => void;
}

const variantClasses: Record<PanelVariant, string> = {
  default: styles.variantDefault,
  accent: styles.variantAccent,
  warning: styles.variantWarning,
  error: styles.variantError,
  hpc: styles.variantHpc,
};

const sizeClasses: Record<PanelSize, string> = {
  sm: styles.panelSm,
  md: styles.panelMd,
  lg: styles.panelLg,
};

export function Panel({
  children,
  title,
  headerActions,
  variant = 'default',
  size = 'md',
  corners = false,
  glowTitle = false,
  interactive = false,
  className = '',
  onClick,
}: PanelProps) {
  const hasHeader = title || headerActions;

  const panelClasses = [
    styles.panel,
    sizeClasses[size],
    variantClasses[variant],
    hasHeader && styles.panelWithHeader,
    corners && styles.corners,
    interactive && styles.interactive,
    className,
  ]
    .filter(Boolean)
    .join(' ');

  const titleClasses = [styles.title, glowTitle && styles.titleGlow]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={panelClasses} onClick={interactive ? onClick : undefined}>
      {hasHeader && (
        <div className={styles.header}>
          {title && <h3 className={titleClasses}>{title}</h3>}
          {headerActions && (
            <div className={styles.headerActions}>{headerActions}</div>
          )}
        </div>
      )}
      <div className={styles.content}>{children}</div>
    </div>
  );
}

export default Panel;
