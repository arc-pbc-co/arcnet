/**
 * EventLog - Scrollable event list with auto-scroll, filtering, and search
 *
 * Displays system events with color coding:
 * - INF (green) - Inference events
 * - HPC (purple) - HPC transfer events
 * - WARN (amber) - Warning events
 * - ERR (red) - Error events
 * - SYS (cyan) - System events
 * - NODE (green dim) - Node status events
 *
 * Features:
 * - Auto-scroll with manual override detection
 * - Filter by event type and severity
 * - Search/filter by text
 * - Event statistics
 * - Pause/resume event stream
 * - Expandable event details
 *
 * Format: [HH:MM:SS] TYPE message
 */

import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { useArcnetStore } from '@/stores/arcnetStore';
import type { ConsoleEvent, EventType, EventSeverity } from '@/types/arcnet';
import styles from './EventLog.module.css';

/**
 * Format timestamp to HH:MM:SS
 */
function formatTime(date: Date): string {
  return date.toISOString().slice(11, 19);
}

/**
 * Get event type label
 */
function getTypeLabel(type: EventType): string {
  switch (type) {
    case 'inference':
      return 'INF';
    case 'hpc':
      return 'HPC';
    case 'node_online':
    case 'node_offline':
    case 'node_stale':
      return 'NODE';
    case 'battery_low':
      return 'BATT';
    case 'geozone_alert':
      return 'GEO';
    case 'system':
      return 'SYS';
    default:
      return 'SYS';
  }
}

/**
 * Get CSS class for event type
 */
function getTypeClass(type: EventType, severity: EventSeverity): string {
  // Severity overrides type for warnings/errors
  if (severity === 'error') return styles.typeError;
  if (severity === 'warn') return styles.typeWarning;

  switch (type) {
    case 'inference':
      return styles.typeInference;
    case 'hpc':
      return styles.typeHpc;
    case 'node_online':
      return styles.typeNodeOnline;
    case 'node_offline':
    case 'node_stale':
      return styles.typeNodeOffline;
    case 'battery_low':
      return styles.typeWarning;
    case 'geozone_alert':
      return styles.typeWarning;
    case 'system':
      return styles.typeSystem;
    default:
      return styles.typeSystem;
  }
}

/**
 * EventEntry - Single event row with expandable details
 */
interface EventEntryProps {
  event: ConsoleEvent;
  isExpanded: boolean;
  onToggleExpand: () => void;
}

function EventEntry({ event, isExpanded, onToggleExpand }: EventEntryProps) {
  const typeLabel = getTypeLabel(event.type);
  const typeClass = getTypeClass(event.type, event.severity);
  const hasDetails = event.details && Object.keys(event.details).length > 0;

  return (
    <div className={styles.entryContainer}>
      <div
        className={`${styles.entry} ${typeClass} ${hasDetails ? styles.clickable : ''}`}
        onClick={hasDetails ? onToggleExpand : undefined}
      >
        <span className={styles.timestamp}>[{formatTime(event.timestamp)}]</span>
        <span className={`${styles.type} ${typeClass}`}>{typeLabel}</span>
        <span className={styles.message}>{event.message}</span>
        {event.nodeId && (
          <span className={styles.nodeId}>{event.nodeId.slice(0, 8)}</span>
        )}
        {hasDetails && (
          <span className={styles.expandIcon}>{isExpanded ? '▼' : '▶'}</span>
        )}
      </div>
      {isExpanded && hasDetails && event.details && (
        <div className={styles.details}>
          {Object.entries(event.details).map(([key, value]) => (
            <div key={key} className={styles.detailRow}>
              <span className={styles.detailKey}>{key}:</span>
              <span className={styles.detailValue}>{JSON.stringify(value)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Mock event generator for testing
 */
function generateMockEvent(): ConsoleEvent {
  const eventTypes: Array<{
    type: EventType;
    severity: EventSeverity;
    messages: string[];
  }> = [
    {
      type: 'inference',
      severity: 'info',
      messages: [
        'Request dispatched to node rubin-west-042',
        'Inference completed: llama-3.1-70b (142ms)',
        'Request routed to geozone us-west',
        'Model llama-3.1-8b loaded successfully',
        'Batch inference: 8 requests processed',
      ],
    },
    {
      type: 'inference',
      severity: 'success',
      messages: [
        'Request completed: 89ms latency',
        'High-priority request served (p99: 45ms)',
        'Inference batch completed: 12 requests',
      ],
    },
    {
      type: 'hpc',
      severity: 'info',
      messages: [
        'Transfer initiated: 450GB dataset to ORNL',
        'Globus transfer: 23% complete',
        'HPC job queued at ORNL Frontier',
        'Transfer completed: 1.2TB in 8m32s',
        'Dataset checkpoint saved',
      ],
    },
    {
      type: 'node_online',
      severity: 'success',
      messages: [
        'Node came online: rubin-east-019',
        'Node reconnected: cogen-az-007',
        'New node joined: edge-ca-042',
      ],
    },
    {
      type: 'node_offline',
      severity: 'warn',
      messages: [
        'Node went offline: grid-tx-003',
        'Connection lost: edge-nv-012',
      ],
    },
    {
      type: 'node_stale',
      severity: 'warn',
      messages: [
        'Node stale: battery-or-008 (no heartbeat)',
        'Telemetry delayed: cogen-nm-015',
      ],
    },
    {
      type: 'battery_low',
      severity: 'warn',
      messages: [
        'Low battery: cogen-az-022 (12%)',
        'Battery critical: edge-nv-005 (5%)',
      ],
    },
    {
      type: 'system',
      severity: 'info',
      messages: [
        'Scheduler rebalancing geozone us-west',
        'Model cache cleared on 3 nodes',
        'Telemetry aggregation complete',
        'Geozone stats updated',
      ],
    },
    {
      type: 'system',
      severity: 'error',
      messages: [
        'Failed to connect to node grid-fl-001',
        'Model load failed: out of VRAM',
        'Request timeout: exceeded 5000ms',
      ],
    },
  ];

  // Weighted random selection (more inference events)
  const weights = [30, 15, 20, 8, 5, 5, 5, 10, 2];
  const totalWeight = weights.reduce((a, b) => a + b, 0);
  let random = Math.random() * totalWeight;

  let selectedIndex = 0;
  for (let i = 0; i < weights.length; i++) {
    random -= weights[i];
    if (random <= 0) {
      selectedIndex = i;
      break;
    }
  }

  const selected = eventTypes[selectedIndex];
  const message =
    selected.messages[Math.floor(Math.random() * selected.messages.length)];

  return {
    id: `evt-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    timestamp: new Date(),
    type: selected.type,
    severity: selected.severity,
    message,
    nodeId:
      selected.type.startsWith('node') || Math.random() > 0.7
        ? `node-${Math.random().toString(36).slice(2, 10)}`
        : undefined,
  };
}

export function EventLog() {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  const [isPaused, setIsPaused] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedTypes, setSelectedTypes] = useState<Set<EventType>>(new Set());
  const [selectedSeverities, setSelectedSeverities] = useState<Set<EventSeverity>>(new Set());
  const [expandedEvents, setExpandedEvents] = useState<Set<string>>(new Set());
  const [showFilters, setShowFilters] = useState(false);

  // Connect to Zustand store
  const events = useArcnetStore((state) => state.events);
  const addEvent = useArcnetStore((state) => state.addEvent);

  // Filter and search events
  const filteredEvents = useMemo(() => {
    let filtered = events;

    // Filter by type
    if (selectedTypes.size > 0) {
      filtered = filtered.filter((event) => selectedTypes.has(event.type));
    }

    // Filter by severity
    if (selectedSeverities.size > 0) {
      filtered = filtered.filter((event) => selectedSeverities.has(event.severity));
    }

    // Search filter
    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter(
        (event) =>
          event.message.toLowerCase().includes(query) ||
          event.nodeId?.toLowerCase().includes(query) ||
          event.type.toLowerCase().includes(query)
      );
    }

    return filtered.slice(0, 100); // Limit to 100 for performance
  }, [events, selectedTypes, selectedSeverities, searchQuery]);

  // Event statistics
  const eventStats = useMemo(() => {
    const stats = {
      total: events.length,
      byType: {} as Record<EventType, number>,
      bySeverity: {} as Record<EventSeverity, number>,
    };

    events.forEach((event) => {
      stats.byType[event.type] = (stats.byType[event.type] || 0) + 1;
      stats.bySeverity[event.severity] = (stats.bySeverity[event.severity] || 0) + 1;
    });

    return stats;
  }, [events]);

  // Auto-scroll to bottom when new events arrive
  useEffect(() => {
    if (autoScroll && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [filteredEvents, autoScroll]);

  // Handle scroll to detect if user scrolled up
  const handleScroll = useCallback(() => {
    if (!scrollRef.current) return;

    const { scrollTop, scrollHeight, clientHeight } = scrollRef.current;
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 50;
    setAutoScroll(isAtBottom);
  }, []);

  // Mock event generation
  useEffect(() => {
    if (isPaused) return;

    const generateEvent = () => {
      const event = generateMockEvent();
      addEvent(event);
    };

    // Generate initial events
    for (let i = 0; i < 10; i++) {
      setTimeout(() => generateEvent(), i * 50);
    }

    // Continue generating events at random intervals
    let timeoutId: ReturnType<typeof setTimeout>;

    const scheduleNext = () => {
      const delay = 200 + Math.random() * 300; // 200-500ms
      timeoutId = setTimeout(() => {
        generateEvent();
        scheduleNext();
      }, delay);
    };

    scheduleNext();

    return () => {
      clearTimeout(timeoutId);
    };
  }, [addEvent, isPaused]);

  // Toggle pause
  const handleTogglePause = () => {
    setIsPaused(!isPaused);
  };

  // Scroll to bottom
  const handleScrollToBottom = () => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
      setAutoScroll(true);
    }
  };

  // Toggle event type filter
  const toggleTypeFilter = (type: EventType) => {
    setSelectedTypes((prev) => {
      const next = new Set(prev);
      if (next.has(type)) {
        next.delete(type);
      } else {
        next.add(type);
      }
      return next;
    });
  };

  // Toggle severity filter
  const toggleSeverityFilter = (severity: EventSeverity) => {
    setSelectedSeverities((prev) => {
      const next = new Set(prev);
      if (next.has(severity)) {
        next.delete(severity);
      } else {
        next.add(severity);
      }
      return next;
    });
  };

  // Clear all filters
  const clearFilters = () => {
    setSelectedTypes(new Set());
    setSelectedSeverities(new Set());
    setSearchQuery('');
  };

  // Toggle event expansion
  const toggleEventExpansion = (eventId: string) => {
    setExpandedEvents((prev) => {
      const next = new Set(prev);
      if (next.has(eventId)) {
        next.delete(eventId);
      } else {
        next.add(eventId);
      }
      return next;
    });
  };

  const hasActiveFilters = selectedTypes.size > 0 || selectedSeverities.size > 0 || searchQuery.trim() !== '';

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <span className={styles.title}>EVENT LOG</span>
        <div className={styles.headerActions}>
          <span className={styles.eventCount}>
            {filteredEvents.length}/{events.length}
          </span>
          <button
            className={`${styles.actionBtn} ${showFilters ? styles.active : ''}`}
            onClick={() => setShowFilters(!showFilters)}
            title="Toggle filters"
          >
            [⚙]
          </button>
          <button
            className={`${styles.actionBtn} ${isPaused ? styles.paused : ''}`}
            onClick={handleTogglePause}
            title={isPaused ? 'Resume' : 'Pause'}
          >
            {isPaused ? '[▶]' : '[⏸]'}
          </button>
          {!autoScroll && (
            <button
              className={styles.actionBtn}
              onClick={handleScrollToBottom}
              title="Scroll to bottom"
            >
              [↓]
            </button>
          )}
        </div>
      </div>

      {/* Filters Panel */}
      {showFilters && (
        <div className={styles.filtersPanel}>
          {/* Search */}
          <div className={styles.filterSection}>
            <input
              type="text"
              className={styles.searchInput}
              placeholder="Search events..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>

          {/* Type Filters */}
          <div className={styles.filterSection}>
            <span className={styles.filterLabel}>Type:</span>
            <div className={styles.filterButtons}>
              {(['inference', 'hpc', 'node_online', 'node_offline', 'system'] as EventType[]).map((type) => (
                <button
                  key={type}
                  className={`${styles.filterBtn} ${selectedTypes.has(type) ? styles.active : ''}`}
                  onClick={() => toggleTypeFilter(type)}
                >
                  {getTypeLabel(type)} ({eventStats.byType[type] || 0})
                </button>
              ))}
            </div>
          </div>

          {/* Severity Filters */}
          <div className={styles.filterSection}>
            <span className={styles.filterLabel}>Severity:</span>
            <div className={styles.filterButtons}>
              {(['info', 'success', 'warn', 'error'] as EventSeverity[]).map((severity) => (
                <button
                  key={severity}
                  className={`${styles.filterBtn} ${styles[`severity${severity.charAt(0).toUpperCase() + severity.slice(1)}`]} ${selectedSeverities.has(severity) ? styles.active : ''}`}
                  onClick={() => toggleSeverityFilter(severity)}
                >
                  {severity.toUpperCase()} ({eventStats.bySeverity[severity] || 0})
                </button>
              ))}
            </div>
          </div>

          {/* Clear Filters */}
          {hasActiveFilters && (
            <button className={styles.clearFiltersBtn} onClick={clearFilters}>
              Clear Filters
            </button>
          )}
        </div>
      )}

      <div
        ref={scrollRef}
        className={styles.eventList}
        onScroll={handleScroll}
      >
        {filteredEvents.length === 0 ? (
          <div className={styles.empty}>
            <span>
              {hasActiveFilters ? 'No events match filters' : 'Waiting for events...'}
            </span>
          </div>
        ) : (
          filteredEvents.map((event) => (
            <EventEntry
              key={event.id}
              event={event}
              isExpanded={expandedEvents.has(event.id)}
              onToggleExpand={() => toggleEventExpansion(event.id)}
            />
          ))
        )}
      </div>

      {/* Auto-scroll indicator */}
      {!autoScroll && (
        <div className={styles.scrollIndicator}>
          <span>New events below</span>
          <button onClick={handleScrollToBottom}>[↓]</button>
        </div>
      )}
    </div>
  );
}

export default EventLog;
