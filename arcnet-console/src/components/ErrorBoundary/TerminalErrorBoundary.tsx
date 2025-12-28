/**
 * TerminalErrorBoundary - CRT-styled error display
 *
 * Catches React errors and displays them in a terminal aesthetic
 * with glitchy effects and stack trace.
 */

import { Component, type ReactNode, type ErrorInfo } from 'react';
import styles from './TerminalErrorBoundary.module.css';

interface ErrorBoundaryProps {
  children: ReactNode;
  /** Fallback component to render (optional) */
  fallback?: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
  errorInfo: ErrorInfo | null;
}

// ASCII art for error state
const ERROR_ASCII = `
  ███████╗██████╗ ██████╗  ██████╗ ██████╗
  ██╔════╝██╔══██╗██╔══██╗██╔═══██╗██╔══██╗
  █████╗  ██████╔╝██████╔╝██║   ██║██████╔╝
  ██╔══╝  ██╔══██╗██╔══██╗██║   ██║██╔══██╗
  ███████╗██║  ██║██║  ██║╚██████╔╝██║  ██║
  ╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝
`;

export class TerminalErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null,
    };
  }

  static getDerivedStateFromError(error: Error): Partial<ErrorBoundaryState> {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('[TerminalErrorBoundary] Caught error:', error, errorInfo);
    this.setState({ errorInfo });
  }

  handleRetry = () => {
    this.setState({
      hasError: false,
      error: null,
      errorInfo: null,
    });
  };

  handleReload = () => {
    window.location.reload();
  };

  render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }

      const { error, errorInfo } = this.state;

      return (
        <div className={styles.container}>
          {/* Glitch overlay */}
          <div className={styles.glitchOverlay} />

          {/* Scanlines */}
          <div className={styles.scanlines} />

          <div className={styles.content}>
            {/* ASCII Error */}
            <pre className={styles.ascii}>{ERROR_ASCII}</pre>

            {/* Error Title */}
            <h1 className={styles.title}>
              <span className={styles.blink}>&gt;</span> SYSTEM MALFUNCTION DETECTED
            </h1>

            {/* Error Code */}
            <div className={styles.errorCode}>
              <span className={styles.label}>ERROR CODE:</span>
              <span className={styles.value}>0x{Math.random().toString(16).slice(2, 10).toUpperCase()}</span>
            </div>

            {/* Divider */}
            <div className={styles.divider}>
              {'━'.repeat(60)}
            </div>

            {/* Error Message */}
            <div className={styles.section}>
              <div className={styles.sectionHeader}>
                <span className={styles.bracket}>[</span>
                ERROR MESSAGE
                <span className={styles.bracket}>]</span>
              </div>
              <pre className={styles.errorMessage}>
                {error?.message || 'Unknown error occurred'}
              </pre>
            </div>

            {/* Stack Trace */}
            {error?.stack && (
              <div className={styles.section}>
                <div className={styles.sectionHeader}>
                  <span className={styles.bracket}>[</span>
                  STACK TRACE
                  <span className={styles.bracket}>]</span>
                </div>
                <pre className={styles.stackTrace}>
                  {error.stack}
                </pre>
              </div>
            )}

            {/* Component Stack */}
            {errorInfo?.componentStack && (
              <div className={styles.section}>
                <div className={styles.sectionHeader}>
                  <span className={styles.bracket}>[</span>
                  COMPONENT STACK
                  <span className={styles.bracket}>]</span>
                </div>
                <pre className={styles.componentStack}>
                  {errorInfo.componentStack}
                </pre>
              </div>
            )}

            {/* Actions */}
            <div className={styles.actions}>
              <button className={styles.button} onClick={this.handleRetry}>
                <span className={styles.buttonIcon}>&gt;</span>
                RETRY
              </button>
              <button className={styles.button} onClick={this.handleReload}>
                <span className={styles.buttonIcon}>&gt;</span>
                RELOAD SYSTEM
              </button>
            </div>

            {/* Footer */}
            <div className={styles.footer}>
              <span className={styles.footerText}>
                ARCNET CONSOLE // RUNTIME ERROR HANDLER
              </span>
              <span className={styles.timestamp}>
                {new Date().toISOString()}
              </span>
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

export default TerminalErrorBoundary;
