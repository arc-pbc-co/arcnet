/**
 * LoginScreen Component - Terminal-styled authentication
 *
 * Simple login screen with username/password fields.
 * Currently supports a single admin user.
 */

import { useState, useRef, useEffect, type FormEvent, type KeyboardEvent } from 'react';
import styles from './LoginScreen.module.css';

// ASCII art logo for login screen
const ASCII_LOGO = `
    _    ____   ____ _   _      _
   / \\  |  _ \\ / ___| \\ | | ___| |_
  / _ \\ | |_) | |   |  \\| |/ _ \\ __|
 / ___ \\|  _ <| |___| |\\  |  __/ |_
/_/   \\_\\_| \\_\\\\____|_| \\_|\\___|\\__|
`;

// Valid credentials (in production, this would be server-side)
const VALID_CREDENTIALS = {
  username: 'admin',
  password: 'wW2Ry7O&kz7^RGhc',
};

export interface LoginScreenProps {
  /** Callback when login succeeds */
  onLoginSuccess: () => void;
}

export function LoginScreen({ onLoginSuccess }: LoginScreenProps) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [isAuthenticating, setIsAuthenticating] = useState(false);
  const [showCursor, setShowCursor] = useState(true);
  const [focusedField, setFocusedField] = useState<'username' | 'password'>('username');

  const usernameRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  // Focus username field on mount
  useEffect(() => {
    usernameRef.current?.focus();
  }, []);

  // Cursor blink effect
  useEffect(() => {
    const interval = setInterval(() => {
      setShowCursor((prev) => !prev);
    }, 530);
    return () => clearInterval(interval);
  }, []);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');

    if (!username.trim()) {
      setError('USERNAME REQUIRED');
      usernameRef.current?.focus();
      return;
    }

    if (!password) {
      setError('PASSWORD REQUIRED');
      passwordRef.current?.focus();
      return;
    }

    setIsAuthenticating(true);

    // Simulate authentication delay for effect
    await new Promise((resolve) => setTimeout(resolve, 1500));

    if (
      username.toLowerCase() === VALID_CREDENTIALS.username &&
      password === VALID_CREDENTIALS.password
    ) {
      // Success - trigger callback
      onLoginSuccess();
    } else {
      setError('ACCESS DENIED: INVALID CREDENTIALS');
      setIsAuthenticating(false);
      setPassword('');
      passwordRef.current?.focus();
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>, field: 'username' | 'password') => {
    if (e.key === 'Enter') {
      if (field === 'username') {
        e.preventDefault();
        passwordRef.current?.focus();
      }
    }
  };

  return (
    <div className={styles.container}>
      {/* Scanlines overlay */}
      <div className={styles.scanlines} />

      {/* Vignette effect */}
      <div className={styles.vignette} />

      <div className={styles.content}>
        {/* ASCII Logo */}
        <pre className={styles.logo}>{ASCII_LOGO}</pre>

        {/* System Title */}
        <div className={styles.title}>
          DISTRIBUTED AI NETWORK OPERATIONS CONSOLE
        </div>

        {/* Divider */}
        <div className={styles.divider}>
          {'═'.repeat(50)}
        </div>

        {/* Security Notice */}
        <div className={styles.securityNotice}>
          <span className={styles.warningIcon}>⚠</span>
          <span>AUTHORIZED PERSONNEL ONLY</span>
        </div>

        {/* Login Form */}
        <form onSubmit={handleSubmit} className={styles.form}>
          <div className={styles.formHeader}>
            <span className={styles.bracket}>[</span>
            SYSTEM LOGIN
            <span className={styles.bracket}>]</span>
          </div>

          {/* Username Field */}
          <div className={styles.field}>
            <label className={styles.label}>USERNAME:</label>
            <div className={styles.inputWrapper}>
              <input
                ref={usernameRef}
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                onFocus={() => setFocusedField('username')}
                onKeyDown={(e) => handleKeyDown(e, 'username')}
                className={styles.input}
                disabled={isAuthenticating}
                spellCheck={false}
                autoComplete="off"
                autoCapitalize="off"
              />
              {focusedField === 'username' && !isAuthenticating && (
                <span className={styles.cursor} style={{ opacity: showCursor ? 1 : 0 }}>
                  █
                </span>
              )}
            </div>
          </div>

          {/* Password Field */}
          <div className={styles.field}>
            <label className={styles.label}>PASSWORD:</label>
            <div className={styles.inputWrapper}>
              <input
                ref={passwordRef}
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                onFocus={() => setFocusedField('password')}
                className={styles.input}
                disabled={isAuthenticating}
                autoComplete="off"
              />
              {focusedField === 'password' && !isAuthenticating && (
                <span className={styles.cursor} style={{ opacity: showCursor ? 1 : 0 }}>
                  █
                </span>
              )}
            </div>
          </div>

          {/* Error Message */}
          {error && (
            <div className={styles.error}>
              <span className={styles.errorIcon}>✖</span>
              {error}
            </div>
          )}

          {/* Authenticating State */}
          {isAuthenticating && (
            <div className={styles.authenticating}>
              <span className={styles.spinner}>◐</span>
              AUTHENTICATING...
            </div>
          )}

          {/* Submit Button */}
          <button
            type="submit"
            className={styles.submitButton}
            disabled={isAuthenticating}
          >
            <span className={styles.buttonIcon}>&gt;</span>
            {isAuthenticating ? 'PROCESSING...' : 'LOGIN'}
          </button>
        </form>

        {/* Footer */}
        <div className={styles.footer}>
          <div className={styles.footerLine}>
            ARCNET CONSOLE v1.0.0 // SECURE ACCESS TERMINAL
          </div>
          <div className={styles.footerLine}>
            {new Date().toISOString().split('T')[0]}
          </div>
        </div>
      </div>
    </div>
  );
}

export default LoginScreen;
