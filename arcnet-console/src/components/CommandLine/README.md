# CommandLine Component

Terminal-style command line interface for the ArcNet console.

## Features

- ✅ Command parsing with flags and arguments
- ✅ Command history navigation (↑/↓)
- ✅ Tab autocomplete
- ✅ Blinking cursor animation
- ✅ Scrollable output area
- ✅ Error highlighting
- ✅ Direct store integration

## Usage

```tsx
import { CommandLine } from '@/components/CommandLine';

function MyComponent() {
  return (
    <div style={{ height: '400px' }}>
      <CommandLine />
    </div>
  );
}
```

## Commands

See [CLI_GUIDE.md](../../../docs/CLI_GUIDE.md) for complete command documentation.

### Quick Reference

- `help` - Show available commands
- `status` - System status
- `nodes [--filter]` - List nodes
- `select <node-id> [--fly]` - Select node
- `route <from> <to>` - Show route
- `jobs [--status]` - List HPC jobs
- `history [--type]` - Event history
- `fly <preset|node-id>` - Fly camera
- `stats [--geozone]` - Statistics
- `events [--limit]` - Recent events
- `clear` - Clear output

## Architecture

### CommandParser.ts
Parses command strings into structured objects:
```typescript
parseCommand("nodes --status=online")
// Returns:
{
  command: "nodes",
  args: [],
  flags: { status: "online" },
  raw: "nodes --status=online"
}
```

### commands.ts
Command handlers that interact with the store:
```typescript
function handleNodes(cmd: ParsedCommand): CommandOutput {
  const state = useArcnetStore.getState();
  // Filter and display nodes
  return { lines: [...], error: false };
}
```

### CommandLine.tsx
React component with:
- Input handling
- History management
- Autocomplete
- Output rendering

## Styling

The component uses CSS modules with a terminal aesthetic:
- Green text on dark background
- Monospace font (Courier New, Consolas)
- Blinking cursor animation
- Scrollable output with custom scrollbar

## Store Integration

Commands directly interact with the Zustand store:

```typescript
// Read state
const state = useArcnetStore.getState();
const nodes = state.nodes;

// Modify state
state.setSelectedNode(nodeId);
state.flyToNode(nodeId);
state.flyToPreset('northAmerica');
```

## Adding Commands

1. Create handler in `commands.ts`:
```typescript
function handleMyCommand(cmd: ParsedCommand): CommandOutput {
  // Validate input
  if (cmd.args.length === 0) {
    return { lines: ['Usage: mycommand <arg>'], error: true };
  }
  
  // Access store
  const state = useArcnetStore.getState();
  
  // Perform action
  // ...
  
  // Return output
  return { lines: ['Success!'] };
}
```

2. Register in `getCommandHandlers()`:
```typescript
export function getCommandHandlers() {
  return {
    mycommand: handleMyCommand,
    // ... other commands
  };
}
```

3. Update help text in `handleHelp()`.

## Examples

### Basic Command
```bash
arcnet> status
Connection: 🟢 CONNECTED
Nodes: 85/100 online
GPUs: 800 total
Avg Utilization: 67.3%
```

### Command with Flags
```bash
arcnet> nodes --status=online --energy=solar
Found 23 node(s):

🟢 CAISO-SanJose-001 [node-001] ☀️ 78% GPU | CAISO
🟢 CAISO-LosAngeles-015 [node-015] ☀️ 82% GPU | CAISO
...
```

### Command with Arguments
```bash
arcnet> select CAISO-SanJose-001 --fly
Selected: CAISO-SanJose-001 [node-001-abc123]
Status: online
Location: CAISO
GPUs: 8 (78.3% utilized)

✈️  Flying to node...
```

## Keyboard Shortcuts

- `↑` / `↓` - Navigate command history
- `Tab` - Autocomplete command
- `Esc` - Clear suggestions
- `Enter` - Execute command

## Testing

```typescript
import { parseCommand, isParseError } from './CommandParser';

// Test parsing
const result = parseCommand('nodes --status=online');
if (!isParseError(result)) {
  console.log(result.command); // "nodes"
  console.log(result.flags.status); // "online"
}

// Test command execution
import { getCommandHandlers } from './commands';
const handlers = getCommandHandlers();
const output = handlers.status({ command: 'status', args: [], flags: {}, raw: 'status' });
console.log(output.lines);
```

## Performance

- Command history limited to 100 entries
- Output auto-scrolls to bottom
- Efficient re-renders using React hooks
- No unnecessary state updates

## Accessibility

- Keyboard navigation
- Focus management
- Clear error messages
- High contrast colors

## Browser Support

- Modern browsers (Chrome, Firefox, Safari, Edge)
- CSS Grid and Flexbox
- ES6+ JavaScript features

