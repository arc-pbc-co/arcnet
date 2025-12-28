/**
 * ARCNet Console - Main Application
 *
 * Terminal-styled operations dashboard for distributed AI network monitoring.
 * Connects to real-time telemetry via WebSocket or uses mock data.
 */

import { useRef, useState, useCallback } from 'react';
import { ConsoleLayout } from '@/layouts';
import { ConsoleHeader } from '@/components/Header';
import { GlobeView } from '@/components/Globe';
import { ResourcePanel } from '@/components/ResourcePanel';
import { NodeDetail } from '@/components/NodeDetail';
import { EventLog } from '@/components/EventLog';
import { CommandLine, type CommandLineHandle } from '@/components/CommandLine';
import { KeyboardShortcutsHelp } from '@/components/KeyboardShortcutsHelp';
import { LoginScreen } from '@/components/LoginScreen';
import { LoadingScreen } from '@/components/LoadingScreen';
import { TerminalErrorBoundary } from '@/components/ErrorBoundary';
import { useArcnetTelemetry, useMockTelemetry, useKeyboardShortcuts } from '@/hooks';

/** Application authentication states */
type AppState = 'login' | 'loading' | 'ready';

// Get WebSocket URL from environment variable
const WS_URL = import.meta.env.VITE_WS_URL || null;
const DEBUG_TELEMETRY = import.meta.env.VITE_DEBUG_TELEMETRY === 'true';

function App() {
  console.log('[App] Starting ArcNet Console');
  console.log('[App] WS_URL:', WS_URL);
  console.log('[App] DEBUG_TELEMETRY:', DEBUG_TELEMETRY);

  // Use real telemetry if WS_URL is provided, otherwise use mock
  const isMockMode = !WS_URL;
  console.log('[App] isMockMode:', isMockMode);

  // Refs and state
  const commandLineRef = useRef<CommandLineHandle>(null);
  const [showHelp, setShowHelp] = useState(false);
  const [appState, setAppState] = useState<AppState>('login');

  // Keyboard shortcuts callbacks
  const handleFocusCommandLine = useCallback(() => {
    commandLineRef.current?.focus();
  }, []);

  const handleToggleHelp = useCallback(() => {
    setShowHelp((prev) => !prev);
  }, []);

  // Login success handler - transition to loading screen
  const handleLoginSuccess = useCallback(() => {
    setAppState('loading');
  }, []);

  // Loading screen completion handler
  const handleLoadingComplete = useCallback(() => {
    setAppState('ready');
  }, []);

  // Initialize keyboard shortcuts (only when app is ready)
  useKeyboardShortcuts({
    onFocusCommandLine: handleFocusCommandLine,
    onToggleHelp: handleToggleHelp,
    enabled: appState === 'ready' && !showHelp,
  });

  // Real telemetry hook (only active if WS_URL is set)
  const { connectionState, reconnect } = useArcnetTelemetry({
    url: WS_URL,
    debug: DEBUG_TELEMETRY,
  });
  console.log('[App] connectionState:', connectionState);

  // Mock telemetry hook (only active if no WS_URL)
  useMockTelemetry({
    enabled: isMockMode,
    interval: 5000,
    debug: DEBUG_TELEMETRY,
  });
  console.log('[App] Mock telemetry enabled:', isMockMode);

  // Show login screen if not authenticated
  if (appState === 'login') {
    return (
      <TerminalErrorBoundary>
        <LoginScreen onLoginSuccess={handleLoginSuccess} />
      </TerminalErrorBoundary>
    );
  }

  return (
    <TerminalErrorBoundary>
      {/* Loading Screen - shown after login */}
      <LoadingScreen
        isVisible={appState === 'loading'}
        onComplete={handleLoadingComplete}
        minDisplayTime={2500}
      />

      {/* Main Application - shown when ready */}
      {appState === 'ready' && (
        <>
          <ConsoleLayout
            header={<ConsoleHeader connectionState={connectionState} isMockMode={isMockMode} onReconnect={reconnect} />}
            globe={<GlobeView />}
            sidebar={
              <>
                <ResourcePanel />
                <NodeDetail />
              </>
            }
            eventLog={<EventLog />}
            commandLine={<CommandLine ref={commandLineRef} />}
          />

          {/* Help Overlay */}
          <KeyboardShortcutsHelp isOpen={showHelp} onClose={() => setShowHelp(false)} />
        </>
      )}
    </TerminalErrorBoundary>
  );
}

export default App;
