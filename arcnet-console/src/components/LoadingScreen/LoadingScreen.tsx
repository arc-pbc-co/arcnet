/**
 * LoadingScreen Component - Boot sequence animation
 *
 * Full-screen loading overlay with ASCII art logo and typewriter
 * initialization text. Shows during initial app boot.
 */

import { useState, useEffect } from 'react';
import styles from './LoadingScreen.module.css';

// ASCII art logo for ARCNet
const ASCII_LOGO = `
    _    ____   ____ _   _      _
   / \\  |  _ \\ / ___| \\ | | ___| |_
  / _ \\ | |_) | |   |  \\| |/ _ \\ __|
 / ___ \\|  _ <| |___| |\\  |  __/ |_
/_/   \\_\\_| \\_\\\\____|_| \\_|\\___|\\__|
`;

const BOOT_MESSAGES = [
  'INITIALIZING ARCNET CONSOLE v1.0.0...',
  'CONNECTING TO MESH NETWORK...',
  'LOADING NODE TELEMETRY...',
  'ESTABLISHING WEBSOCKET CONNECTION...',
  'RENDERING GLOBE VISUALIZATION...',
  'SYSTEM READY.',
];

export interface LoadingScreenProps {
  /** Whether the loading screen is visible */
  isVisible: boolean;
  /** Callback when loading completes */
  onComplete?: () => void;
  /** Minimum display time in ms */
  minDisplayTime?: number;
}

export function LoadingScreen({
  isVisible,
  onComplete,
  minDisplayTime = 2500,
}: LoadingScreenProps) {
  const [currentLine, setCurrentLine] = useState(0);
  const [displayedText, setDisplayedText] = useState('');
  const [showCursor, setShowCursor] = useState(true);
  const [isFadingOut, setIsFadingOut] = useState(false);
  const [isHidden, setIsHidden] = useState(false);

  // Typewriter effect for current message
  useEffect(() => {
    if (!isVisible || currentLine >= BOOT_MESSAGES.length) return;

    const message = BOOT_MESSAGES[currentLine];
    let charIndex = 0;

    const typeInterval = setInterval(() => {
      if (charIndex <= message.length) {
        setDisplayedText(message.slice(0, charIndex));
        charIndex++;
      } else {
        clearInterval(typeInterval);
        // Move to next line after a short delay
        setTimeout(() => {
          if (currentLine < BOOT_MESSAGES.length - 1) {
            setCurrentLine((prev) => prev + 1);
            setDisplayedText('');
          }
        }, 300);
      }
    }, 30);

    return () => clearInterval(typeInterval);
  }, [isVisible, currentLine]);

  // Cursor blink effect
  useEffect(() => {
    const cursorInterval = setInterval(() => {
      setShowCursor((prev) => !prev);
    }, 530);
    return () => clearInterval(cursorInterval);
  }, []);

  // Handle completion after minimum display time
  useEffect(() => {
    if (!isVisible) return;

    const timer = setTimeout(() => {
      setIsFadingOut(true);
      // Wait for fade animation to complete, then hide and call onComplete
      setTimeout(() => {
        setIsHidden(true);
        onComplete?.();
      }, 500);
    }, minDisplayTime);

    return () => clearTimeout(timer);
  }, [isVisible, minDisplayTime, onComplete]);

  // Don't render if hidden or if not visible and not fading
  if (isHidden || (!isVisible && !isFadingOut)) return null;

  return (
    <div className={`${styles.overlay} ${isFadingOut ? styles.fadeOut : ''}`}>
      <div className={styles.content}>
        {/* ASCII Logo */}
        <pre className={styles.logo}>{ASCII_LOGO}</pre>

        {/* Subtitle */}
        <div className={styles.subtitle}>
          DISTRIBUTED AI NETWORK OPERATIONS CONSOLE
        </div>

        {/* Divider */}
        <div className={styles.divider}>
          {'═'.repeat(50)}
        </div>

        {/* Boot messages */}
        <div className={styles.bootLog}>
          {BOOT_MESSAGES.slice(0, currentLine).map((msg, i) => (
            <div key={i} className={styles.bootLine}>
              <span className={styles.prefix}>[OK]</span>
              <span className={styles.message}>{msg}</span>
            </div>
          ))}

          {/* Current typing line */}
          {currentLine < BOOT_MESSAGES.length && (
            <div className={styles.bootLine}>
              <span className={styles.prefixActive}>[..]</span>
              <span className={styles.messageActive}>
                {displayedText}
                <span className={styles.cursor} style={{ opacity: showCursor ? 1 : 0 }}>
                  █
                </span>
              </span>
            </div>
          )}
        </div>

        {/* Progress bar */}
        <div className={styles.progressContainer}>
          <div className={styles.progressLabel}>LOADING</div>
          <div className={styles.progressBar}>
            <div
              className={styles.progressFill}
              style={{
                width: `${(currentLine / BOOT_MESSAGES.length) * 100}%`
              }}
            />
          </div>
          <div className={styles.progressPercent}>
            {Math.round((currentLine / BOOT_MESSAGES.length) * 100)}%
          </div>
        </div>
      </div>

      {/* Scanlines overlay for extra CRT feel */}
      <div className={styles.scanlines} />
    </div>
  );
}

export default LoadingScreen;
