# ArcNet Command Line Interface (CLI) Guide

## Overview

The ArcNet CLI provides a terminal-style interface for interacting with the distributed AI network. It supports command parsing, history navigation, tab autocomplete, and direct integration with the Zustand store.

## Features

### ✨ Core Features

- **Command Parsing**: Parse commands with flags and arguments
- **Command History**: Navigate history with ↑/↓ arrow keys
- **Tab Autocomplete**: Press Tab to autocomplete commands
- **Blinking Cursor**: Classic terminal-style cursor animation
- **Store Integration**: Commands directly affect the global state
- **Error Handling**: Clear error messages for invalid commands

### 🎨 Visual Features

- Terminal-style green text on dark background
- Scrollable output area
- Suggestion display for autocomplete
- Error highlighting in red
- Responsive design

## Available Commands

### `help`
Show all available commands and usage examples.

```bash
arcnet> help
```

### `status`
Display system status and statistics.

```bash
arcnet> status
```

**Output:**
- Connection status
- Node counts (online/total)
- Total GPUs
- Average GPU utilization
- Active inference arcs
- HPC transfers
- Global statistics

### `nodes [--filter]`
List nodes with optional filters.

**Flags:**
- `--status=<value>`: Filter by status (online, busy, idle, stale, offline)
- `--energy=<value>`: Filter by energy source (solar, grid, battery)
- `--geozone=<region>`: Filter by geozone/ISO region

**Examples:**
```bash
arcnet> nodes
arcnet> nodes --status=online
arcnet> nodes --energy=solar
arcnet> nodes --status=online --energy=solar
arcnet> nodes --geozone=CAISO
```

**Output:**
- Node name and ID
- Status icon (🟢 online, 🟡 busy, ⚪ idle, 🟠 stale, 🔴 offline)
- Energy source icon (☀️ solar, ⚡ grid, 🔋 battery)
- GPU utilization percentage
- Geozone/region

### `select <node-id> [--fly]`
Select a node and optionally fly the camera to it.

**Arguments:**
- `<node-id>`: Node ID or name (supports partial matching)

**Flags:**
- `--fly`: Fly camera to the selected node

**Examples:**
```bash
arcnet> select node-001
arcnet> select CAISO-SanJose-001 --fly
arcnet> select node-042 --fly
```

**Output:**
- Node name and ID
- Status
- Location (geozone)
- GPU information

### `route <from> <to>`
Calculate and display route between two nodes.

**Arguments:**
- `<from>`: Source node ID or name
- `<to>`: Destination node ID or name

**Examples:**
```bash
arcnet> route node-001 node-042
arcnet> route CAISO-SanJose-001 PJM-WashingtonDC-042
```

**Output:**
- Route description
- Distance in kilometers
- Source coordinates and geozone
- Destination coordinates and geozone

### `jobs [--status]`
List HPC jobs and transfers.

**Flags:**
- `--status=<value>`: Filter by status (pending, transferring, queued, running, completed, failed)

**Examples:**
```bash
arcnet> jobs
arcnet> jobs --status=running
arcnet> jobs --status=completed
```

**Output:**
- Job status
- Job ID
- Progress percentage

### `history [--type] [--limit]`
Show event history.

**Flags:**
- `--type=<value>`: Filter by event type
- `--limit=<number>`: Number of events to show (default: 10)

**Examples:**
```bash
arcnet> history
arcnet> history --limit=20
arcnet> history --type=inference
```

**Output:**
- Timestamp
- Event type
- Event message

### `fly <preset|node-id>`
Fly camera to a preset location or node.

**Arguments:**
- `<preset>`: Preset name (global, northAmerica, europe, asia, ornl)
- `<node-id>`: Node ID or name

**Examples:**
```bash
arcnet> fly global
arcnet> fly northAmerica
arcnet> fly CAISO-SanJose-001
arcnet> fly node-042
```

### `stats [--geozone]`
Show statistics.

**Flags:**
- `--geozone=<region>`: Show stats for specific geozone

**Examples:**
```bash
arcnet> stats
arcnet> stats --geozone=CAISO
arcnet> stats --geozone=PJM
```

**Output:**
- Global or geozone-specific statistics
- Node counts by region
- Request statistics

### `events [--limit]`
Show recent events.

**Flags:**
- `--limit=<number>`: Number of events to show (default: 10)

**Examples:**
```bash
arcnet> events
arcnet> events --limit=20
```

**Output:**
- Event severity icon (ℹ️ info, ⚠️ warning, ❌ error)
- Timestamp
- Event message

### `clear`
Clear the command output area.

```bash
arcnet> clear
```

## Command Syntax

### Basic Command
```bash
command
```

### Command with Arguments
```bash
command arg1 arg2 arg3
```

### Command with Flags
```bash
command --flag=value
command --boolean-flag
```

### Combined
```bash
command arg1 arg2 --flag1=value --flag2
```

## Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `↑` | Previous command in history |
| `↓` | Next command in history |
| `Tab` | Autocomplete command |
| `Esc` | Clear suggestions |
| `Enter` | Execute command |

## Architecture

### File Structure
```
src/components/CommandLine/
├── CommandParser.ts       # Command parsing logic
├── commands.ts            # Command handlers
├── CommandLine.tsx        # React component
├── CommandLine.module.css # Styles
└── index.ts              # Public API
```

### Command Parser
The parser tokenizes input and extracts:
- Command name
- Arguments (positional)
- Flags (--key=value or --boolean)

### Command Handlers
Each command has a handler function that:
1. Validates input
2. Accesses the Zustand store
3. Performs actions (select nodes, fly camera, etc.)
4. Returns formatted output

### Store Integration
Commands interact with the store using:
- `useArcnetStore.getState()` - Read current state
- Store actions - Modify state (setSelectedNode, flyToNode, etc.)

## Adding New Commands

1. **Add handler to `commands.ts`:**
```typescript
function handleMyCommand(cmd: ParsedCommand): CommandOutput {
  const state = useArcnetStore.getState();
  // Your logic here
  return { lines: ['Output line 1', 'Output line 2'] };
}
```

2. **Register in `getCommandHandlers()`:**
```typescript
export function getCommandHandlers(): Record<string, CommandHandler> {
  return {
    // ... existing commands
    mycommand: handleMyCommand,
  };
}
```

3. **Update help text** in `handleHelp()`.

## Examples

### Find and select a solar-powered node
```bash
arcnet> nodes --energy=solar
arcnet> select CAISO-SanJose-001 --fly
```

### Check system status and view events
```bash
arcnet> status
arcnet> events --limit=20
```

### Navigate to different regions
```bash
arcnet> fly northAmerica
arcnet> fly europe
arcnet> fly asia
```

### Monitor HPC jobs
```bash
arcnet> jobs --status=running
arcnet> jobs --status=completed
```

## Tips

- Use **partial matching** for node IDs: `select node-001` works for `node-001-abc123`
- **Tab autocomplete** works for command names
- **Arrow keys** navigate command history
- Commands are **case-insensitive**
- Use `--fly` flag with `select` to automatically fly to nodes

