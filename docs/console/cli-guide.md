# ArcNet Console CLI Guide

## Overview

The ArcNet Console includes a powerful terminal-style command-line interface for network operations, queries, and management. The CLI provides tab completion, command history, and real-time feedback.

## Getting Started

### Opening the CLI

The CLI is located at the bottom of the console interface. Click the terminal area or press `` ` `` (backtick) to focus.

### Basic Usage

```bash
arcnet> help
arcnet> status
arcnet> nodes
```

### Features

- **Command History**: Use ↑/↓ arrows to navigate previous commands
- **Tab Completion**: Press Tab to autocomplete commands and arguments
- **Syntax Highlighting**: Commands are color-coded for readability
- **Real-Time Suggestions**: See available commands as you type
- **Output Formatting**: Results displayed in structured, readable format

## Command Reference

### System Commands

#### `help`

Display all available commands with descriptions.

```bash
arcnet> help
```

**Output**:
```
Available Commands:
  status    - Display global network statistics
  nodes     - List all nodes with optional filters
  select    - Select a node by ID
  fly       - Fly camera to preset location
  events    - Show recent events
  jobs      - List HPC jobs
  stats     - Show statistics
  history   - Show command history
  clear     - Clear command output
  help      - Show this help message
```

#### `status`

Display global network statistics.

```bash
arcnet> status
```

**Output**:
```
Network Status:
  Nodes Online:        142 / 150
  COGEN Powered:       68%
  Avg GPU Util:        42%
  Inference RPS:       1,247
  P99 Latency:         245ms
  HPC Jobs Pending:    3
  HPC Jobs Running:    2
```

#### `clear`

Clear the command output area.

```bash
arcnet> clear
```

#### `history`

Show command history.

```bash
arcnet> history
```

**Output**:
```
Command History:
  1. status
  2. nodes --status=online
  3. select node-001
  4. fly northAmerica
  5. events --limit=20
```

### Node Management

#### `nodes`

List all nodes with optional filtering.

**Syntax**:
```bash
nodes [--status=<status>] [--energy=<source>] [--geozone=<zone>] [--limit=<n>]
```

**Examples**:

```bash
# List all nodes
arcnet> nodes

# Filter by status
arcnet> nodes --status=online
arcnet> nodes --status=busy
arcnet> nodes --status=offline

# Filter by energy source
arcnet> nodes --energy=cogen
arcnet> nodes --energy=grid
arcnet> nodes --energy=battery

# Filter by geozone
arcnet> nodes --geozone=CAISO
arcnet> nodes --geozone=ERCOT

# Combine filters
arcnet> nodes --status=online --energy=cogen --geozone=CAISO

# Limit results
arcnet> nodes --limit=10
```

**Output**:
```
Nodes (142 online):
  ID              Geozone  Status  Energy  Battery  GPU    Models
  node-001        CAISO    online  cogen   87%      42%    llama-3-70b, sd-xl
  node-002        CAISO    online  grid    -        65%    llama-3-70b
  node-003        ERCOT    busy    cogen   92%      88%    gpt-4, sd-xl
  ...
```

#### `select`

Select a node to view details and optionally fly camera to it.

**Syntax**:
```bash
select <node-id> [--fly]
```

**Examples**:

```bash
# Select node
arcnet> select node-001

# Select and fly camera to node
arcnet> select node-001 --fly
```

**Output**:
```
Selected Node: node-001
  Name:           CAISO-SanJose-042
  Geozone:        CAISO
  Location:       37.7749°N, 122.4194°W
  Status:         online
  Energy Source:  cogen
  Battery Level:  87%
  GPU Util:       42%
  GPU Memory:     32.5 GB free
  Models Loaded:  llama-3-70b, stable-diffusion-xl
  Last Seen:      2 seconds ago
```

### Camera Control

#### `fly`

Fly camera to preset location.

**Syntax**:
```bash
fly <preset>
```

**Presets**:
- `global` - Global view showing entire Earth
- `northAmerica` - North America view
- `europe` - Europe view
- `asia` - Asia view
- `ornl` - Oak Ridge National Laboratory

**Examples**:

```bash
arcnet> fly global
arcnet> fly northAmerica
arcnet> fly ornl
```

**Output**:
```
Flying to: North America
```

### Event Management

#### `events`

Show recent events with optional filtering.

**Syntax**:
```bash
events [--limit=<n>] [--severity=<level>] [--type=<type>]
```

**Examples**:

```bash
# Show recent events (default: 20)
arcnet> events

# Show more events
arcnet> events --limit=50

# Filter by severity
arcnet> events --severity=error
arcnet> events --severity=warn

# Filter by type
arcnet> events --type=inference
arcnet> events --type=system
arcnet> events --type=hpc
```

**Output**:
```
Recent Events (20):
  [18:45:32] INFO    Node node-001 came online
  [18:45:35] SUCCESS Inference completed in 245ms
  [18:45:40] WARN    Low battery on node-003 (15%)
  [18:45:45] INFO    HPC transfer initiated for job-12345
  ...
```

### HPC Management

#### `jobs`

List HPC jobs with optional filtering.

**Syntax**:
```bash
jobs [--status=<status>] [--limit=<n>]
```

**Examples**:

```bash
# List all jobs
arcnet> jobs

# Filter by status
arcnet> jobs --status=pending
arcnet> jobs --status=transferring
arcnet> jobs --status=running
arcnet> jobs --status=completed

# Limit results
arcnet> jobs --limit=5
```

**Output**:
```
HPC Jobs (5 active):
  ID          Status        Dataset Size  Progress  ETA
  job-12345   transferring  150.5 GB      65%       15m
  job-12346   pending       85.2 GB       0%        -
  job-12347   running       200.0 GB      100%      -
  job-12348   queued        120.0 GB      100%      -
  job-12349   completed     95.5 GB       100%      -
```

### Statistics

#### `stats`

Show network statistics with optional geozone filtering.

**Syntax**:
```bash
stats [--geozone=<zone>]
```

**Examples**:

```bash
# Global statistics
arcnet> stats

# Geozone-specific statistics
arcnet> stats --geozone=CAISO
arcnet> stats --geozone=ERCOT
```

**Output**:
```
Network Statistics:

Nodes:
  Total:        150
  Online:       142 (95%)
  Busy:         38 (25%)
  Idle:         104 (69%)
  Offline:      8 (5%)

Energy:
  COGEN:        68%
  Grid:         25%
  Battery:      7%

GPU:
  Avg Util:     42%
  Max Util:     95%
  Min Util:     5%

Inference:
  Total Requests:   1,247,892
  Requests/sec:     1,247
  Avg Latency:      187ms
  P99 Latency:      245ms
  Success Rate:     99.8%

HPC:
  Pending Jobs:     3
  Running Jobs:     2
  Completed Jobs:   127
```

### Network Operations

#### `route`

Calculate route between two nodes (future feature).

**Syntax**:
```bash
route <from-node-id> <to-node-id>
```

**Example**:

```bash
arcnet> route node-001 node-042
```

**Output**:
```
Route: node-001 → node-042
  Distance:       2,847 km
  Latency:        ~28ms
  Hops:           direct
  Energy Cost:    0.15 kWh
```

## Advanced Usage

### Command Chaining

Execute multiple commands in sequence:

```bash
arcnet> nodes --status=online; select node-001 --fly
```

### Output Redirection (Future)

Save command output to file:

```bash
arcnet> nodes --status=online > online-nodes.txt
```

### Scripting (Future)

Execute commands from file:

```bash
arcnet> source commands.txt
```

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `` ` `` | Focus CLI |
| `↑` / `↓` | Navigate command history |
| `Tab` | Autocomplete command |
| `Ctrl+C` | Cancel current command |
| `Ctrl+L` | Clear output (same as `clear`) |
| `Ctrl+U` | Clear current line |
| `Esc` | Unfocus CLI |

## Tips and Tricks

### Quick Node Selection

```bash
# Select first online COGEN node
arcnet> nodes --status=online --energy=cogen --limit=1
arcnet> select <node-id> --fly
```

### Monitor Specific Geozone

```bash
# Show CAISO statistics
arcnet> stats --geozone=CAISO

# List CAISO nodes
arcnet> nodes --geozone=CAISO
```

### Track HPC Jobs

```bash
# Show pending transfers
arcnet> jobs --status=pending

# Show running jobs
arcnet> jobs --status=running
```

### Find High-Utilization Nodes

```bash
# List busy nodes
arcnet> nodes --status=busy

# Check their details
arcnet> select <node-id>
```

## Error Messages

| Error | Meaning | Solution |
|-------|---------|----------|
| `Unknown command: <cmd>` | Command not recognized | Check spelling or use `help` |
| `Node not found: <id>` | Node ID doesn't exist | Use `nodes` to list valid IDs |
| `Invalid argument: <arg>` | Argument format incorrect | Check command syntax |
| `No nodes match filters` | No results for query | Adjust filter criteria |

## Next Steps

- **[Console Overview](index.md)** - Learn about other console features
- **[Telemetry Integration](telemetry.md)** - Understand data flow
- **[Customization](customization.md)** - Customize CLI appearance

