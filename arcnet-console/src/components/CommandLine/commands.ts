/**
 * Command Handlers
 * 
 * Implements all CLI commands that interact with the ArcNet store.
 */

import type { ParsedCommand } from './CommandParser';
import { validateFlag } from './CommandParser';
import { useArcnetStore, VIEW_PRESETS, type ViewPresetKey } from '@/stores/arcnetStore';
import type { Node, NodeStatus, EnergySource } from '@/types/arcnet';

export interface CommandOutput {
  lines: string[];
  error?: boolean;
}

export type CommandHandler = (cmd: ParsedCommand) => CommandOutput;

/**
 * Get all command handlers
 */
export function getCommandHandlers(): Record<string, CommandHandler> {
  return {
    help: handleHelp,
    status: handleStatus,
    nodes: handleNodes,
    select: handleSelect,
    route: handleRoute,
    jobs: handleJobs,
    history: handleHistory,
    fly: handleFly,
    clear: handleClear,
    stats: handleStats,
    events: handleEvents,
  };
}

/**
 * Get command suggestions for autocomplete
 */
export function getCommandSuggestions(partial: string): string[] {
  const commands = Object.keys(getCommandHandlers());
  
  if (!partial) {
    return commands;
  }
  
  return commands.filter(cmd => cmd.startsWith(partial.toLowerCase()));
}

/**
 * help - Show available commands
 */
function handleHelp(cmd: ParsedCommand): CommandOutput {
  const lines = [
    'Available Commands:',
    '',
    '  status                    - Show system status and statistics',
    '  nodes [--filter]          - List nodes (--status=online|busy|idle|stale)',
    '                              --energy=cogen|grid|battery, --geozone=REGION',
    '  select <node-id> [--fly]  - Select a node (optionally fly to it)',
    '  route <from> <to>         - Show route between two nodes',
    '  jobs [--status]           - List HPC jobs (--status=pending|running|completed)',
    '  history [--type]          - Show event history (--type=inference|hpc|node)',
    '  fly <preset|node-id>      - Fly to location (global|northAmerica|europe|asia|ornl)',
    '  stats [--geozone]         - Show statistics (optionally for specific geozone)',
    '  events [--limit=N]        - Show recent events (default: 10)',
    '  clear                     - Clear command output',
    '  help                      - Show this help message',
    '',
    'Examples:',
    '  nodes --status=online --energy=cogen',
    '  select node-001-abc123 --fly',
    '  fly northAmerica',
    '  jobs --status=running',
  ];
  
  return { lines };
}

/**
 * status - Show system status
 */
function handleStatus(cmd: ParsedCommand): CommandOutput {
  const state = useArcnetStore.getState();
  const { nodes, inferenceArcs, hpcTransfers, globalStats, isConnected } = state;
  
  const onlineNodes = nodes.filter(n => n.status === 'online' || n.status === 'busy').length;
  const totalGpus = nodes.reduce((sum, n) => sum + n.gpuCount, 0);
  const avgUtilization = nodes.length > 0
    ? (nodes.reduce((sum, n) => sum + n.gpuUtilization, 0) / nodes.length * 100).toFixed(1)
    : '0.0';
  
  const lines = [
    `Connection: ${isConnected ? '🟢 CONNECTED' : '🔴 DISCONNECTED'}`,
    `Nodes: ${onlineNodes}/${nodes.length} online`,
    `GPUs: ${totalGpus} total`,
    `Avg Utilization: ${avgUtilization}%`,
    `Active Inference Arcs: ${inferenceArcs.length}`,
    `HPC Transfers: ${hpcTransfers.length}`,
  ];
  
  if (globalStats) {
    lines.push('', 'Global Stats:');
    lines.push(`  Total Requests: ${globalStats.totalRequests}`);
    lines.push(`  Completed: ${globalStats.completedRequests}`);
    lines.push(`  Failed: ${globalStats.failedRequests}`);
  }
  
  return { lines };
}

/**
 * nodes - List nodes with optional filters
 */
function handleNodes(cmd: ParsedCommand): CommandOutput {
  const state = useArcnetStore.getState();
  let nodes = state.nodes;
  
  // Apply filters
  if (cmd.flags.status) {
    const statusError = validateFlag(cmd.flags, 'status', ['online', 'busy', 'idle', 'stale', 'offline']);
    if (statusError) {
      return { lines: [statusError], error: true };
    }
    nodes = nodes.filter(n => n.status === cmd.flags.status);
  }
  
  if (cmd.flags.energy) {
    const energyError = validateFlag(cmd.flags, 'energy', ['cogen', 'grid', 'battery']);
    if (energyError) {
      return { lines: [energyError], error: true };
    }
    nodes = nodes.filter(n => n.energySource === cmd.flags.energy);
  }
  
  if (cmd.flags.geozone) {
    nodes = nodes.filter(n => n.geozone === cmd.flags.geozone);
  }
  
  const lines = [`Found ${nodes.length} node(s):`, ''];
  
  nodes.slice(0, 20).forEach(node => {
    const status = getStatusIcon(node.status);
    const energy = getEnergyIcon(node.energySource);
    const util = (node.gpuUtilization * 100).toFixed(0);
    lines.push(`${status} ${node.name} [${node.id.slice(0, 8)}] ${energy} ${util}% GPU | ${node.geozone}`);
  });
  
  if (nodes.length > 20) {
    lines.push('', `... and ${nodes.length - 20} more. Use filters to narrow results.`);
  }

  return { lines };
}

/**
 * select - Select a node and optionally fly to it
 */
function handleSelect(cmd: ParsedCommand): CommandOutput {
  if (cmd.args.length === 0) {
    return { lines: ['Usage: select <node-id> [--fly]'], error: true };
  }

  const state = useArcnetStore.getState();
  const nodeIdOrName = cmd.args[0];

  // Find node by ID or name
  const node = state.nodes.find(n =>
    n.id === nodeIdOrName ||
    n.id.startsWith(nodeIdOrName) ||
    n.name === nodeIdOrName ||
    n.name.toLowerCase().includes(nodeIdOrName.toLowerCase())
  );

  if (!node) {
    return { lines: [`Node not found: ${nodeIdOrName}`], error: true };
  }

  // Select the node
  state.setSelectedNode(node.id);

  const lines = [
    `Selected: ${node.name} [${node.id}]`,
    `Status: ${node.status}`,
    `Location: ${node.geozone}`,
    `GPUs: ${node.gpuCount} (${(node.gpuUtilization * 100).toFixed(1)}% utilized)`,
  ];

  // Fly to node if requested
  if (cmd.flags.fly) {
    state.flyToNode(node.id);
    lines.push('', '✈️  Flying to node...');
  }

  return { lines };
}

/**
 * route - Show route between two nodes
 */
function handleRoute(cmd: ParsedCommand): CommandOutput {
  if (cmd.args.length < 2) {
    return { lines: ['Usage: route <from-node> <to-node>'], error: true };
  }

  const state = useArcnetStore.getState();
  const fromId = cmd.args[0];
  const toId = cmd.args[1];

  const fromNode = state.nodes.find(n => n.id.startsWith(fromId) || n.name.includes(fromId));
  const toNode = state.nodes.find(n => n.id.startsWith(toId) || n.name.includes(toId));

  if (!fromNode) {
    return { lines: [`Source node not found: ${fromId}`], error: true };
  }

  if (!toNode) {
    return { lines: [`Destination node not found: ${toId}`], error: true };
  }

  // Calculate distance
  const [lon1, lat1] = fromNode.position;
  const [lon2, lat2] = toNode.position;
  const distance = calculateDistance(lat1, lon1, lat2, lon2);

  const lines = [
    `Route: ${fromNode.name} → ${toNode.name}`,
    `Distance: ${distance.toFixed(0)} km`,
    `From: ${fromNode.geozone} [${lon1.toFixed(2)}, ${lat1.toFixed(2)}]`,
    `To: ${toNode.geozone} [${lon2.toFixed(2)}, ${lat2.toFixed(2)}]`,
  ];

  return { lines };
}

/**
 * jobs - List HPC jobs/transfers
 */
function handleJobs(cmd: ParsedCommand): CommandOutput {
  const state = useArcnetStore.getState();
  let transfers = state.hpcTransfers;

  if (cmd.flags.status) {
    const statusError = validateFlag(cmd.flags, 'status', ['pending', 'transferring', 'queued', 'running', 'completed', 'failed']);
    if (statusError) {
      return { lines: [statusError], error: true };
    }
    transfers = transfers.filter(t => t.status === cmd.flags.status);
  }

  const lines = [`Found ${transfers.length} HPC job(s):`, ''];

  transfers.slice(0, 15).forEach(transfer => {
    const status = transfer.status.toUpperCase();
    const progress = transfer.progress ? `${(transfer.progress * 100).toFixed(0)}%` : 'N/A';
    lines.push(`[${status}] ${transfer.id.slice(0, 8)} - ${progress}`);
  });

  if (transfers.length > 15) {
    lines.push('', `... and ${transfers.length - 15} more.`);
  }

  return { lines };
}

/**
 * history - Show event history
 */
function handleHistory(cmd: ParsedCommand): CommandOutput {
  const state = useArcnetStore.getState();
  let events = state.events;

  if (cmd.flags.type) {
    events = events.filter(e => e.type === cmd.flags.type);
  }

  const limit = cmd.flags.limit ? parseInt(cmd.flags.limit as string, 10) : 10;

  const lines = [`Recent events (showing ${Math.min(limit, events.length)}):`, ''];

  events.slice(0, limit).forEach(event => {
    const time = new Date(event.timestamp).toLocaleTimeString();
    lines.push(`[${time}] ${event.type}: ${event.message}`);
  });

  return { lines };
}

/**
 * fly - Fly to a preset location or node
 */
function handleFly(cmd: ParsedCommand): CommandOutput {
  if (cmd.args.length === 0) {
    return {
      lines: [
        'Usage: fly <preset|node-id>',
        '',
        'Available presets:',
        '  global, northAmerica, europe, asia, ornl'
      ],
      error: true
    };
  }

  const state = useArcnetStore.getState();
  const target = cmd.args[0];

  // Check if it's a preset
  if (target in VIEW_PRESETS) {
    state.flyToPreset(target as ViewPresetKey);
    return { lines: [`✈️  Flying to ${target}...`] };
  }

  // Try to find node
  const node = state.nodes.find(n =>
    n.id.startsWith(target) ||
    n.name.toLowerCase().includes(target.toLowerCase())
  );

  if (node) {
    state.flyToNode(node.id);
    return { lines: [`✈️  Flying to ${node.name}...`] };
  }

  return { lines: [`Unknown location or node: ${target}`], error: true };
}

/**
 * clear - Clear command output
 */
function handleClear(cmd: ParsedCommand): CommandOutput {
  return { lines: ['__CLEAR__'] };
}

/**
 * stats - Show statistics
 */
function handleStats(cmd: ParsedCommand): CommandOutput {
  const state = useArcnetStore.getState();

  if (cmd.flags.geozone) {
    const geozone = cmd.flags.geozone as string;
    const stats = state.geozoneStats.get(geozone);

    if (!stats) {
      return { lines: [`No statistics available for geozone: ${geozone}`], error: true };
    }

    return {
      lines: [
        `Statistics for ${geozone}:`,
        `  Total Nodes: ${stats.totalNodes}`,
        `  Active Nodes: ${stats.activeNodes}`,
      ]
    };
  }

  // Global stats
  const { globalStats, nodes } = state;
  const geozones = [...new Set(nodes.map(n => n.geozone))];

  const lines = ['Global Statistics:', ''];

  if (globalStats) {
    lines.push(`Total Requests: ${globalStats.totalRequests}`);
    lines.push(`Completed: ${globalStats.completedRequests}`);
    lines.push(`Failed: ${globalStats.failedRequests}`);
    lines.push('');
  }

  lines.push(`Geozones: ${geozones.length}`);
  geozones.forEach(gz => {
    const count = nodes.filter(n => n.geozone === gz).length;
    lines.push(`  ${gz}: ${count} nodes`);
  });

  return { lines };
}

/**
 * events - Show recent events
 */
function handleEvents(cmd: ParsedCommand): CommandOutput {
  const state = useArcnetStore.getState();
  const limit = cmd.flags.limit ? parseInt(cmd.flags.limit as string, 10) : 10;
  const events = state.events.slice(0, limit);

  const lines = [`Recent ${limit} events:`, ''];

  events.forEach(event => {
    const time = new Date(event.timestamp).toLocaleTimeString();
    const severity = event.severity || 'info';
    const icon = severity === 'error' ? '❌' : severity === 'warning' ? '⚠️' : 'ℹ️';
    lines.push(`${icon} [${time}] ${event.message}`);
  });

  if (events.length === 0) {
    lines.push('No events to display.');
  }

  return { lines };
}

// =============================================================================
// Helper Functions
// =============================================================================

function getStatusIcon(status: NodeStatus): string {
  switch (status) {
    case 'online': return '🟢';
    case 'busy': return '🟡';
    case 'idle': return '⚪';
    case 'stale': return '🟠';
    case 'offline': return '🔴';
    default: return '⚫';
  }
}

function getEnergyIcon(energy: EnergySource): string {
  switch (energy) {
    case 'cogen': return '⚡';
    case 'grid': return '🔌';
    case 'battery': return '🔋';
    default: return '❓';
  }
}

function calculateDistance(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371; // Earth's radius in km
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

