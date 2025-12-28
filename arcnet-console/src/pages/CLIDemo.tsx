/**
 * CLI Demo Page
 * 
 * Standalone page showcasing the CommandLine interface with example commands
 * and interactive tutorial.
 */

import { useState } from 'react';
import { CommandLine } from '@/components/CommandLine';
import styles from './CLIDemo.module.css';

const EXAMPLE_COMMANDS = [
  { cmd: 'help', desc: 'Show all available commands' },
  { cmd: 'status', desc: 'Display system status and statistics' },
  { cmd: 'nodes', desc: 'List all nodes' },
  { cmd: 'nodes --status=online', desc: 'Filter nodes by status' },
  { cmd: 'nodes --energy=cogen', desc: 'Show COGEN-powered nodes' },
  { cmd: 'nodes --status=online --energy=cogen', desc: 'Multiple filters' },
  { cmd: 'select node-001', desc: 'Select a node' },
  { cmd: 'select node-001 --fly', desc: 'Select and fly to node' },
  { cmd: 'route node-001 node-042', desc: 'Calculate route between nodes' },
  { cmd: 'fly northAmerica', desc: 'Fly to North America' },
  { cmd: 'fly global', desc: 'Fly to global view' },
  { cmd: 'jobs', desc: 'List HPC jobs' },
  { cmd: 'jobs --status=running', desc: 'Filter jobs by status' },
  { cmd: 'events', desc: 'Show recent events' },
  { cmd: 'events --limit=20', desc: 'Show more events' },
  { cmd: 'history', desc: 'Show event history' },
  { cmd: 'stats', desc: 'Show global statistics' },
  { cmd: 'stats --geozone=CAISO', desc: 'Show geozone statistics' },
  { cmd: 'clear', desc: 'Clear the output' },
];

const KEYBOARD_SHORTCUTS = [
  { key: '↑ / ↓', desc: 'Navigate command history' },
  { key: 'Tab', desc: 'Autocomplete command' },
  { key: 'Esc', desc: 'Clear suggestions' },
  { key: 'Enter', desc: 'Execute command' },
];

export function CLIDemo() {
  const [showHelp, setShowHelp] = useState(true);

  return (
    <div className={styles.demoPage}>
      {/* Header */}
      <header className={styles.header}>
        <div className={styles.headerLeft}>
          <a
            href="#"
            className={styles.backButton}
            title="Back to ArcNet Console"
          >
            ← Back to Console
          </a>
          <h1 className={styles.title}>
            <span className={styles.titleIcon}>⌨️</span>
            ArcNet CLI Demo
          </h1>
        </div>
        <button
          className={styles.toggleHelp}
          onClick={() => setShowHelp(!showHelp)}
        >
          {showHelp ? 'Hide' : 'Show'} Help
        </button>
      </header>

      <div className={styles.content}>
        {/* Help Panel */}
        {showHelp && (
          <aside className={styles.helpPanel}>
            <section className={styles.helpSection}>
              <h2 className={styles.helpTitle}>📋 Example Commands</h2>
              <div className={styles.commandList}>
                {EXAMPLE_COMMANDS.map((example, i) => (
                  <div key={i} className={styles.commandExample}>
                    <code className={styles.commandCode}>{example.cmd}</code>
                    <span className={styles.commandDesc}>{example.desc}</span>
                  </div>
                ))}
              </div>
            </section>

            <section className={styles.helpSection}>
              <h2 className={styles.helpTitle}>⌨️ Keyboard Shortcuts</h2>
              <div className={styles.shortcutList}>
                {KEYBOARD_SHORTCUTS.map((shortcut, i) => (
                  <div key={i} className={styles.shortcut}>
                    <kbd className={styles.key}>{shortcut.key}</kbd>
                    <span className={styles.shortcutDesc}>{shortcut.desc}</span>
                  </div>
                ))}
              </div>
            </section>

            <section className={styles.helpSection}>
              <h2 className={styles.helpTitle}>💡 Quick Tips</h2>
              <ul className={styles.tipsList}>
                <li>Type <code>help</code> to see all commands</li>
                <li>Use <code>--flag=value</code> for filters</li>
                <li>Commands are case-insensitive</li>
                <li>Partial node IDs work (e.g., <code>node-001</code>)</li>
                <li>Press Tab for autocomplete</li>
                <li>Use ↑/↓ to navigate history</li>
              </ul>
            </section>

            <section className={styles.helpSection}>
              <h2 className={styles.helpTitle}>🎯 Try These Sequences</h2>
              <div className={styles.sequenceList}>
                <div className={styles.sequence}>
                  <h3>Find and Select a Node</h3>
                  <code>nodes --status=online</code>
                  <code>select node-001 --fly</code>
                </div>
                <div className={styles.sequence}>
                  <h3>Monitor System</h3>
                  <code>status</code>
                  <code>events --limit=20</code>
                  <code>jobs --status=running</code>
                </div>
                <div className={styles.sequence}>
                  <h3>Navigate Globe</h3>
                  <code>fly northAmerica</code>
                  <code>fly europe</code>
                  <code>fly global</code>
                </div>
              </div>
            </section>
          </aside>
        )}

        {/* CLI Terminal */}
        <main className={styles.terminal}>
          <div className={styles.terminalHeader}>
            <span className={styles.terminalTitle}>ArcNet Terminal</span>
            <span className={styles.terminalStatus}>● CONNECTED</span>
          </div>
          <div className={styles.terminalBody}>
            <CommandLine />
          </div>
        </main>
      </div>

      {/* Footer */}
      <footer className={styles.footer}>
        <p>
          📚 Full documentation: <code>docs/CLI_GUIDE.md</code>
        </p>
        <p>
          🔗 Integration guide: <code>docs/CLI_INTEGRATION.md</code>
        </p>
      </footer>
    </div>
  );
}

export default CLIDemo;

