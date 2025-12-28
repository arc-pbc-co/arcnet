/**
 * useKeyboardShortcuts - Global keyboard shortcuts for ARCNet Console
 *
 * Shortcuts:
 * - Escape: Deselect node
 * - 1: Fly to global view
 * - 2: Fly to North America
 * - 3: Fly to Europe
 * - 4: Fly to Asia
 * - O: Fly to ORNL
 * - /: Focus command line
 * - ?: Show shortcuts help overlay
 */

import { useEffect, useCallback } from 'react';
import { useArcnetStore } from '@/stores/arcnetStore';

export interface UseKeyboardShortcutsOptions {
  /** Callback to focus the command line input */
  onFocusCommandLine?: () => void;
  /** Callback to toggle help overlay */
  onToggleHelp?: () => void;
  /** Whether shortcuts are enabled (default: true) */
  enabled?: boolean;
}

/**
 * Check if the event target is an input element
 */
function isInputElement(target: EventTarget | null): boolean {
  if (!target || !(target instanceof HTMLElement)) return false;
  const tagName = target.tagName.toLowerCase();
  return (
    tagName === 'input' ||
    tagName === 'textarea' ||
    tagName === 'select' ||
    target.isContentEditable
  );
}

export function useKeyboardShortcuts(options: UseKeyboardShortcutsOptions = {}) {
  const { onFocusCommandLine, onToggleHelp, enabled = true } = options;

  const setSelectedNode = useArcnetStore((state) => state.setSelectedNode);
  const flyToPreset = useArcnetStore((state) => state.flyToPreset);

  const handleKeyDown = useCallback(
    (event: KeyboardEvent) => {
      // Ignore if shortcuts are disabled
      if (!enabled) return;

      // Ignore if focused on an input element (except for Escape)
      if (event.key !== 'Escape' && isInputElement(event.target)) return;

      // Ignore if modifier keys are pressed (except for ?)
      if (event.key !== '?' && (event.ctrlKey || event.metaKey || event.altKey)) return;

      switch (event.key) {
        case 'Escape':
          // Deselect node
          setSelectedNode(null);
          // Also blur any focused input
          if (document.activeElement instanceof HTMLElement) {
            document.activeElement.blur();
          }
          break;

        case '1':
          // Global view
          flyToPreset('global');
          break;

        case '2':
          // North America
          flyToPreset('northAmerica');
          break;

        case '3':
          // Europe
          flyToPreset('europe');
          break;

        case '4':
          // Asia
          flyToPreset('asia');
          break;

        case 'o':
        case 'O':
          // ORNL
          flyToPreset('ornl');
          break;

        case '/':
          // Focus command line
          event.preventDefault();
          onFocusCommandLine?.();
          break;

        case '?':
          // Toggle help overlay
          event.preventDefault();
          onToggleHelp?.();
          break;

        default:
          return;
      }
    },
    [enabled, setSelectedNode, flyToPreset, onFocusCommandLine, onToggleHelp]
  );

  useEffect(() => {
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [handleKeyDown]);
}

/**
 * Keyboard shortcut definitions for display in help overlay
 */
export const KEYBOARD_SHORTCUTS = [
  { key: 'Esc', description: 'Deselect node / Close overlay' },
  { key: '1', description: 'Global view' },
  { key: '2', description: 'North America view' },
  { key: '3', description: 'Europe view' },
  { key: '4', description: 'Asia view' },
  { key: 'O', description: 'ORNL view' },
  { key: '/', description: 'Focus command line' },
  { key: '?', description: 'Show this help' },
] as const;
