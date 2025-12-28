/**
 * TypedText Component - Typewriter text animation
 *
 * Animates text as if being typed character by character,
 * with configurable speed, cursor style, and callbacks.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import styles from './TypedText.module.css';

export type CursorStyle = 'block' | 'line' | 'underscore';
export type TextColor = 'green' | 'cyan' | 'warning' | 'error' | 'hpc' | 'white';
export type TextSize = 'sm' | 'md' | 'lg' | 'xl';

export interface TypedTextProps {
  /** Text to display/animate */
  text: string;
  /** Typing speed in ms per character */
  speed?: number;
  /** Delay before starting animation (ms) */
  delay?: number;
  /** Show cursor */
  showCursor?: boolean;
  /** Cursor style */
  cursorStyle?: CursorStyle;
  /** Hide cursor after typing completes */
  hideCursorOnComplete?: boolean;
  /** Text color */
  color?: TextColor;
  /** Text size */
  size?: TextSize;
  /** Add glow effect */
  glow?: boolean;
  /** Optional prefix (e.g., "> " or "$ ") */
  prefix?: string;
  /** Callback when typing starts */
  onStart?: () => void;
  /** Callback when typing completes */
  onComplete?: () => void;
  /** Skip animation, show full text immediately */
  skipAnimation?: boolean;
  /** Additional CSS class */
  className?: string;
}

const cursorClasses: Record<CursorStyle, string> = {
  block: styles.cursorBlock,
  line: styles.cursorLine,
  underscore: styles.cursorUnderscore,
};

const colorClasses: Record<TextColor, string> = {
  green: styles.colorGreen,
  cyan: styles.colorCyan,
  warning: styles.colorWarning,
  error: styles.colorError,
  hpc: styles.colorHpc,
  white: styles.colorWhite,
};

const sizeClasses: Record<TextSize, string> = {
  sm: styles.sizeSm,
  md: styles.sizeMd,
  lg: styles.sizeLg,
  xl: styles.sizeXl,
};

export function TypedText({
  text,
  speed = 50,
  delay = 0,
  showCursor = true,
  cursorStyle = 'block',
  hideCursorOnComplete = false,
  color = 'green',
  size = 'md',
  glow = false,
  prefix,
  onStart,
  onComplete,
  skipAnimation = false,
  className = '',
}: TypedTextProps) {
  const [displayedText, setDisplayedText] = useState(skipAnimation ? text : '');
  const [, setIsTyping] = useState(false);
  const [isComplete, setIsComplete] = useState(skipAnimation);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const indexRef = useRef(0);

  const typeNextChar = useCallback(() => {
    if (indexRef.current < text.length) {
      setDisplayedText(text.slice(0, indexRef.current + 1));
      indexRef.current += 1;

      // Variable speed for more natural feel
      const variance = Math.random() * 30 - 15; // -15 to +15ms
      const nextDelay = Math.max(10, speed + variance);

      timeoutRef.current = setTimeout(typeNextChar, nextDelay);
    } else {
      setIsTyping(false);
      setIsComplete(true);
      onComplete?.();
    }
  }, [text, speed, onComplete]);

  useEffect(() => {
    if (skipAnimation) {
      setDisplayedText(text);
      setIsComplete(true);
      return;
    }

    // Reset on text change
    setDisplayedText('');
    setIsComplete(false);
    indexRef.current = 0;

    // Start typing after delay
    const startTimeout = setTimeout(() => {
      setIsTyping(true);
      onStart?.();
      typeNextChar();
    }, delay);

    return () => {
      clearTimeout(startTimeout);
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, [text, delay, skipAnimation, typeNextChar, onStart]);

  const containerClasses = [
    styles.container,
    colorClasses[color],
    sizeClasses[size],
    glow && styles.glowing,
    isComplete && styles.completed,
    className,
  ]
    .filter(Boolean)
    .join(' ');

  const cursorClassList = [
    styles.cursor,
    cursorClasses[cursorStyle],
    (hideCursorOnComplete && isComplete) && styles.cursorHidden,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <span className={containerClasses}>
      {prefix && <span className={styles.prefix}>{prefix}</span>}
      <span className={styles.text}>{displayedText}</span>
      {showCursor && <span className={cursorClassList} />}
    </span>
  );
}

/**
 * Hook for programmatic typing control
 */
export function useTypedText(text: string, speed = 50) {
  const [displayedText, setDisplayedText] = useState('');
  const [isComplete, setIsComplete] = useState(false);
  const indexRef = useRef(0);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const start = useCallback(() => {
    indexRef.current = 0;
    setDisplayedText('');
    setIsComplete(false);

    const type = () => {
      if (indexRef.current < text.length) {
        setDisplayedText(text.slice(0, indexRef.current + 1));
        indexRef.current += 1;
        timeoutRef.current = setTimeout(type, speed);
      } else {
        setIsComplete(true);
      }
    };

    type();
  }, [text, speed]);

  const reset = useCallback(() => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    indexRef.current = 0;
    setDisplayedText('');
    setIsComplete(false);
  }, []);

  const complete = useCallback(() => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    setDisplayedText(text);
    setIsComplete(true);
  }, [text]);

  useEffect(() => {
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
  }, []);

  return { displayedText, isComplete, start, reset, complete };
}

/**
 * Sequence multiple typed texts
 */
export interface TypedSequenceItem {
  text: string;
  speed?: number;
  delay?: number;
  color?: TextColor;
}

export function TypedSequence({
  items,
  onAllComplete,
}: {
  items: TypedSequenceItem[];
  onAllComplete?: () => void;
}) {
  const [currentIndex, setCurrentIndex] = useState(0);
  const [completedTexts, setCompletedTexts] = useState<string[]>([]);

  const handleComplete = useCallback(() => {
    setCompletedTexts((prev) => [...prev, items[currentIndex].text]);

    if (currentIndex < items.length - 1) {
      setCurrentIndex((prev) => prev + 1);
    } else {
      onAllComplete?.();
    }
  }, [currentIndex, items, onAllComplete]);

  return (
    <div>
      {completedTexts.map((text, i) => (
        <div key={i}>
          <TypedText
            text={text}
            skipAnimation
            showCursor={false}
            color={items[i].color}
          />
        </div>
      ))}
      {currentIndex < items.length && (
        <div>
          <TypedText
            text={items[currentIndex].text}
            speed={items[currentIndex].speed}
            delay={items[currentIndex].delay}
            color={items[currentIndex].color}
            onComplete={handleComplete}
          />
        </div>
      )}
    </div>
  );
}

export default TypedText;
