/**
 * Command Line Interface Component
 *
 * Terminal-style CLI with command history, autocomplete, and blinking cursor.
 */

import React, { useState, useRef, useEffect, useCallback, forwardRef, useImperativeHandle } from 'react';
import { parseCommand, isParseError } from './CommandParser';
import { getCommandHandlers, getCommandSuggestions } from './commands';
import { useArcnetStore } from '@/stores/arcnetStore';
import styles from './CommandLine.module.css';

interface OutputLine {
  text: string;
  error?: boolean;
  timestamp: Date;
}

export interface CommandLineHandle {
  focus: () => void;
}

export const CommandLine = forwardRef<CommandLineHandle>(function CommandLine(_props, ref) {
  const [input, setInput] = useState('');
  const [output, setOutput] = useState<OutputLine[]>([
    { text: 'ArcNet Command Line Interface v1.0', timestamp: new Date() },
    { text: 'Type "help" for available commands', timestamp: new Date() },
    { text: '', timestamp: new Date() },
  ]);
  const [historyIndex, setHistoryIndex] = useState(-1);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [showCursor, setShowCursor] = useState(true);
  const [isExpanded, setIsExpanded] = useState(false);
  
  const inputRef = useRef<HTMLInputElement>(null);
  const outputRef = useRef<HTMLDivElement>(null);
  const commandHistory = useArcnetStore((state) => state.commandHistory);
  const addToCommandHistory = useArcnetStore((state) => state.addToCommandHistory);

  // Blinking cursor effect
  useEffect(() => {
    const interval = setInterval(() => {
      setShowCursor((prev) => !prev);
    }, 530);
    return () => clearInterval(interval);
  }, []);

  // Auto-scroll to bottom when output changes
  useEffect(() => {
    if (outputRef.current) {
      outputRef.current.scrollTop = outputRef.current.scrollHeight;
    }
  }, [output]);

  // Expose focus method via ref
  useImperativeHandle(ref, () => ({
    focus: () => {
      inputRef.current?.focus();
    },
  }));

  // Focus input on mount
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const addOutput = useCallback((lines: string[], error = false) => {
    const newLines = lines.map(text => ({
      text,
      error,
      timestamp: new Date(),
    }));
    setOutput((prev) => [...prev, ...newLines]);
  }, []);

  const handleCommand = useCallback((cmd: string) => {
    const trimmed = cmd.trim();
    if (!trimmed) return;

    // Add command to output
    addOutput([`arcnet> ${trimmed}`]);

    // Parse command
    const parsed = parseCommand(trimmed);
    
    if (isParseError(parsed)) {
      addOutput([`Error: ${parsed.message}`], true);
      return;
    }

    // Execute command
    const handlers = getCommandHandlers();
    const handler = handlers[parsed.command];
    
    if (!handler) {
      addOutput([`Unknown command: ${parsed.command}`, 'Type "help" for available commands'], true);
      return;
    }

    try {
      const result = handler(parsed);
      
      // Handle clear command
      if (result.lines.length === 1 && result.lines[0] === '__CLEAR__') {
        setOutput([]);
        return;
      }
      
      addOutput(result.lines, result.error);
      addOutput(['']); // Empty line after output
    } catch (error) {
      addOutput([`Error executing command: ${error instanceof Error ? error.message : String(error)}`], true);
    }
  }, [addOutput]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim()) return;

    // Auto-expand when user submits a command
    if (!isExpanded) {
      setIsExpanded(true);
    }

    handleCommand(input);
    addToCommandHistory(input);
    setInput('');
    setHistoryIndex(-1);
    setSuggestions([]);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    // Command history navigation
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (commandHistory.length === 0) return;
      
      const newIndex = historyIndex === -1 
        ? commandHistory.length - 1 
        : Math.max(0, historyIndex - 1);
      
      setHistoryIndex(newIndex);
      setInput(commandHistory[newIndex]);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (historyIndex === -1) return;
      
      const newIndex = historyIndex + 1;
      
      if (newIndex >= commandHistory.length) {
        setHistoryIndex(-1);
        setInput('');
      } else {
        setHistoryIndex(newIndex);
        setInput(commandHistory[newIndex]);
      }
    } else if (e.key === 'Tab') {
      e.preventDefault();
      
      // Autocomplete
      const trimmed = input.trim();
      if (!trimmed) {
        setSuggestions(getCommandSuggestions(''));
        return;
      }
      
      const words = trimmed.split(' ');
      if (words.length === 1) {
        const matches = getCommandSuggestions(words[0]);
        if (matches.length === 1) {
          setInput(matches[0] + ' ');
          setSuggestions([]);
        } else if (matches.length > 1) {
          setSuggestions(matches);
        }
      }
    } else if (e.key === 'Escape') {
      setSuggestions([]);
    }
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setInput(e.target.value);
    setSuggestions([]);
  };

  return (
    <div className={`${styles.commandLine} ${isExpanded ? styles.expanded : ''}`}>
      {/* Output area - only shown when expanded */}
      {isExpanded && (
        <div ref={outputRef} className={styles.output}>
          {output.map((line, i) => (
            <div
              key={i}
              className={line.error ? styles.errorLine : styles.outputLine}
            >
              {line.text}
            </div>
          ))}
        </div>
      )}

      {/* Suggestions - only shown when expanded */}
      {isExpanded && suggestions.length > 0 && (
        <div className={styles.suggestions}>
          {suggestions.map((suggestion, i) => (
            <span key={i} className={styles.suggestion}>
              {suggestion}
            </span>
          ))}
        </div>
      )}

      {/* Input form with expand/collapse button - always visible */}
      <form onSubmit={handleSubmit} className={styles.inputForm}>
        <button
          type="button"
          className={styles.toggleBtn}
          onClick={() => setIsExpanded(!isExpanded)}
          title={isExpanded ? 'Minimize' : 'Expand'}
        >
          {isExpanded ? '[−]' : '[+]'}
        </button>
        <span className={styles.prompt}>arcnet&gt;</span>
        <input
          ref={inputRef}
          type="text"
          value={input}
          onChange={handleInputChange}
          onKeyDown={handleKeyDown}
          className={styles.input}
          spellCheck={false}
          autoComplete="off"
        />
        <span className={styles.cursor} style={{ opacity: showCursor ? 1 : 0 }}>
          █
        </span>
      </form>
    </div>
  );
});
