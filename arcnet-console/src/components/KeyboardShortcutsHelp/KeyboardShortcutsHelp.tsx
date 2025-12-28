/**
 * KeyboardShortcutsHelp - Modal overlay showing keyboard shortcuts
 */

import { useEffect, useCallback } from 'react';
import { KEYBOARD_SHORTCUTS } from '@/hooks/useKeyboardShortcuts';
import styles from './KeyboardShortcutsHelp.module.css';

interface KeyboardShortcutsHelpProps {
  isOpen: boolean;
  onClose: () => void;
}

export function KeyboardShortcutsHelp({ isOpen, onClose }: KeyboardShortcutsHelpProps) {
  const handleKeyDown = useCallback(
    (event: KeyboardEvent) => {
      if (event.key === 'Escape' || event.key === '?') {
        event.preventDefault();
        onClose();
      }
    },
    [onClose]
  );

  useEffect(() => {
    if (isOpen) {
      window.addEventListener('keydown', handleKeyDown);
      return () => {
        window.removeEventListener('keydown', handleKeyDown);
      };
    }
  }, [isOpen, handleKeyDown]);

  if (!isOpen) return null;

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        <div className={styles.header}>
          <h2 className={styles.title}>Keyboard Shortcuts</h2>
          <button className={styles.closeButton} onClick={onClose}>
            ESC
          </button>
        </div>

        <div className={styles.content}>
          <div className={styles.shortcutList}>
            {KEYBOARD_SHORTCUTS.map(({ key, description }) => (
              <div key={key} className={styles.shortcutRow}>
                <kbd className={styles.key}>{key}</kbd>
                <span className={styles.description}>{description}</span>
              </div>
            ))}
          </div>

          <div className={styles.divider} />

          <div className={styles.section}>
            <h3 className={styles.sectionTitle}>Command Line</h3>
            <div className={styles.shortcutList}>
              <div className={styles.shortcutRow}>
                <kbd className={styles.key}>Enter</kbd>
                <span className={styles.description}>Execute command</span>
              </div>
              <div className={styles.shortcutRow}>
                <kbd className={styles.key}>Up/Down</kbd>
                <span className={styles.description}>Navigate history</span>
              </div>
              <div className={styles.shortcutRow}>
                <kbd className={styles.key}>Tab</kbd>
                <span className={styles.description}>Autocomplete</span>
              </div>
            </div>
          </div>
        </div>

        <div className={styles.footer}>
          <span className={styles.hint}>Press ? or ESC to close</span>
        </div>
      </div>
    </div>
  );
}
