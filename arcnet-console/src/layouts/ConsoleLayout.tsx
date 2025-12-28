/**
 * ConsoleLayout - Main mission control grid layout
 *
 * Provides the overall structure for the ARCNet Console with:
 * - Header with system status
 * - Globe visualization (deck.gl)
 * - Sidebar with resource panel and node details
 * - Event log
 * - Command line interface
 * - CRT scan line overlay
 */

import type { ReactNode } from 'react';
import { ScanLines } from '@/components/shared';
import { useArcnetStore } from '@/stores/arcnetStore';
import styles from './ConsoleLayout.module.css';

export interface ConsoleLayoutProps {
  /** Header component */
  header?: ReactNode;
  /** Globe visualization component */
  globe?: ReactNode;
  /** Sidebar content (resource panel, node details) */
  sidebar?: ReactNode;
  /** Event log component */
  eventLog?: ReactNode;
  /** Command line component */
  commandLine?: ReactNode;
  /** Show loading overlay */
  isLoading?: boolean;
  /** Loading message */
  loadingMessage?: string;
}

/**
 * Placeholder component for development
 */
function Placeholder({ label }: { label: string }) {
  return (
    <div className={styles.placeholder}>
      <span className={styles.placeholderLabel}>{label}</span>
    </div>
  );
}

export function ConsoleLayout({
  header,
  globe,
  sidebar,
  eventLog,
  commandLine,
  isLoading = false,
  loadingMessage = 'Initializing ARCNet Console...',
}: ConsoleLayoutProps) {
  const showScanlines = useArcnetStore((state) => state.showScanlines);

  return (
    <div className={`${styles.layout} ${styles.crtCurve}`}>
      {/* Header */}
      <header className={styles.header}>
        {header ?? <Placeholder label="Header" />}
      </header>

      {/* Globe Visualization */}
      <main className={styles.globe}>
        {globe ?? <Placeholder label="Globe Visualization" />}
      </main>

      {/* Sidebar */}
      <aside className={styles.sidebar}>
        {sidebar ?? (
          <>
            <div className={styles.sidebarPanel}>
              <Placeholder label="Resource Panel" />
            </div>
            <div className={styles.sidebarPanel}>
              <Placeholder label="Node Detail" />
            </div>
          </>
        )}
      </aside>

      {/* Event Log */}
      <section className={styles.events}>
        {eventLog ?? <Placeholder label="Event Log" />}
      </section>

      {/* Command Line */}
      <footer className={styles.command}>
        {commandLine ?? <Placeholder label="Command Line" />}
      </footer>

      {/* CRT Scan Lines Overlay - Light scanlines only for compatibility */}
      <ScanLines
        enabled={showScanlines}
        intensity="light"
        flicker={false}
        vignette={false}
        rgbShift={false}
        phosphorGlow={false}
      />

      {/* Loading Overlay */}
      {isLoading && (
        <div className={styles.loadingOverlay}>
          <span className={styles.loadingText}>{loadingMessage}</span>
        </div>
      )}
    </div>
  );
}

export default ConsoleLayout;
