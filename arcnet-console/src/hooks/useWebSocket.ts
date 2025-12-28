/**
 * useWebSocket - WebSocket connection with auto-reconnect
 * 
 * Features:
 * - Automatic reconnection with exponential backoff
 * - Connection state management
 * - Message queue during disconnection
 * - Heartbeat/ping support
 * - Error handling and logging
 */

import { useEffect, useRef, useState, useCallback } from 'react';

export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'error';

export interface UseWebSocketOptions {
  /** WebSocket URL to connect to */
  url: string | null;
  /** Enable automatic reconnection (default: true) */
  autoReconnect?: boolean;
  /** Initial reconnect delay in ms (default: 1000) */
  reconnectDelay?: number;
  /** Maximum reconnect delay in ms (default: 30000) */
  maxReconnectDelay?: number;
  /** Heartbeat interval in ms (default: 30000) */
  heartbeatInterval?: number;
  /** Called when connection opens */
  onOpen?: () => void;
  /** Called when connection closes */
  onClose?: () => void;
  /** Called on connection error */
  onError?: (error: Event) => void;
  /** Called when message received */
  onMessage?: (data: unknown) => void;
}

export interface UseWebSocketReturn {
  /** Current connection state */
  state: ConnectionState;
  /** Send a message (queued if disconnected) */
  send: (data: unknown) => void;
  /** Manually reconnect */
  reconnect: () => void;
  /** Manually disconnect */
  disconnect: () => void;
}

export function useWebSocket(options: UseWebSocketOptions): UseWebSocketReturn {
  const {
    url,
    autoReconnect = true,
    reconnectDelay = 1000,
    maxReconnectDelay = 30000,
    heartbeatInterval = 30000,
    onOpen,
    onClose,
    onError,
    onMessage,
  } = options;

  const [state, setState] = useState<ConnectionState>('disconnected');
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const heartbeatIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const messageQueueRef = useRef<unknown[]>([]);
  const shouldReconnectRef = useRef(true);

  const clearTimers = useCallback(() => {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    if (heartbeatIntervalRef.current) {
      clearInterval(heartbeatIntervalRef.current);
      heartbeatIntervalRef.current = null;
    }
  }, []);

  const connect = useCallback(() => {
    if (!url) {
      setState('disconnected');
      return;
    }

    if (wsRef.current?.readyState === WebSocket.OPEN) {
      return;
    }

    setState('connecting');
    clearTimers();

    try {
      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        console.log('[WebSocket] Connected to', url);
        setState('connected');
        reconnectAttemptsRef.current = 0;
        
        // Flush message queue
        while (messageQueueRef.current.length > 0) {
          const msg = messageQueueRef.current.shift();
          if (msg) {
            ws.send(JSON.stringify(msg));
          }
        }

        // Start heartbeat
        heartbeatIntervalRef.current = setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'ping' }));
          }
        }, heartbeatInterval);

        onOpen?.();
      };

      ws.onclose = () => {
        console.log('[WebSocket] Disconnected');
        setState('disconnected');
        clearTimers();
        onClose?.();

        // Auto-reconnect with exponential backoff
        if (autoReconnect && shouldReconnectRef.current) {
          const delay = Math.min(
            reconnectDelay * Math.pow(2, reconnectAttemptsRef.current),
            maxReconnectDelay
          );
          reconnectAttemptsRef.current++;
          console.log(`[WebSocket] Reconnecting in ${delay}ms (attempt ${reconnectAttemptsRef.current})`);
          
          reconnectTimeoutRef.current = setTimeout(() => {
            connect();
          }, delay);
        }
      };

      ws.onerror = (error) => {
        console.error('[WebSocket] Error:', error);
        setState('error');
        onError?.(error);
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          onMessage?.(data);
        } catch (error) {
          console.error('[WebSocket] Failed to parse message:', error);
        }
      };
    } catch (error) {
      console.error('[WebSocket] Connection failed:', error);
      setState('error');
    }
  }, [url, autoReconnect, reconnectDelay, maxReconnectDelay, heartbeatInterval, onOpen, onClose, onError, onMessage, clearTimers]);

  const disconnect = useCallback(() => {
    shouldReconnectRef.current = false;
    clearTimers();
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    setState('disconnected');
  }, [clearTimers]);

  const reconnect = useCallback(() => {
    shouldReconnectRef.current = true;
    reconnectAttemptsRef.current = 0;
    disconnect();
    setTimeout(connect, 100);
  }, [connect, disconnect]);

  const send = useCallback((data: unknown) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(data));
    } else {
      // Queue message for later
      messageQueueRef.current.push(data);
    }
  }, []);

  // Connect on mount, disconnect on unmount
  useEffect(() => {
    if (url) {
      connect();
    }
    return () => {
      shouldReconnectRef.current = false;
      disconnect();
    };
  }, [url, connect, disconnect]);

  return {
    state,
    send,
    reconnect,
    disconnect,
  };
}

