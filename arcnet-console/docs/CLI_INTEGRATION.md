# CLI Integration Guide

## Quick Start

### 1. Import the Component

```tsx
import { CommandLine } from '@/components/CommandLine';
```

### 2. Add to Your Layout

```tsx
function ConsoleLayout() {
  return (
    <div className="layout">
      {/* Other components */}
      
      <div className="cli-container" style={{ height: '300px' }}>
        <CommandLine />
      </div>
    </div>
  );
}
```

### 3. Style the Container

The CommandLine component needs a defined height to work properly:

```css
.cli-container {
  height: 300px; /* or any fixed height */
  min-height: 200px;
  max-height: 600px;
  resize: vertical; /* Allow user to resize */
  overflow: hidden;
}
```

## Integration Examples

### Example 1: Bottom Panel

```tsx
function ConsoleLayout() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      {/* Main content */}
      <div style={{ flex: 1 }}>
        <Globe />
      </div>
      
      {/* CLI at bottom */}
      <div style={{ height: '250px', borderTop: '1px solid rgba(0, 255, 100, 0.3)' }}>
        <CommandLine />
      </div>
    </div>
  );
}
```

### Example 2: Side Panel

```tsx
function ConsoleLayout() {
  return (
    <div style={{ display: 'flex', height: '100vh' }}>
      {/* Main content */}
      <div style={{ flex: 1 }}>
        <Globe />
      </div>
      
      {/* CLI on right side */}
      <div style={{ width: '400px', borderLeft: '1px solid rgba(0, 255, 100, 0.3)' }}>
        <CommandLine />
      </div>
    </div>
  );
}
```

### Example 3: Collapsible Panel

```tsx
function ConsoleLayout() {
  const [showCLI, setShowCLI] = useState(true);
  
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      {/* Main content */}
      <div style={{ flex: 1 }}>
        <Globe />
      </div>
      
      {/* Toggle button */}
      <button onClick={() => setShowCLI(!showCLI)}>
        {showCLI ? 'Hide' : 'Show'} CLI
      </button>
      
      {/* CLI panel */}
      {showCLI && (
        <div style={{ height: '300px' }}>
          <CommandLine />
        </div>
      )}
    </div>
  );
}
```

### Example 4: Resizable Panel

```tsx
import { useState } from 'react';

function ConsoleLayout() {
  const [cliHeight, setCLIHeight] = useState(300);
  
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      {/* Main content */}
      <div style={{ flex: 1 }}>
        <Globe />
      </div>
      
      {/* Resize handle */}
      <div
        style={{
          height: '4px',
          background: 'rgba(0, 255, 100, 0.3)',
          cursor: 'ns-resize',
        }}
        onMouseDown={(e) => {
          const startY = e.clientY;
          const startHeight = cliHeight;
          
          const handleMouseMove = (e: MouseEvent) => {
            const delta = startY - e.clientY;
            setCLIHeight(Math.max(200, Math.min(600, startHeight + delta)));
          };
          
          const handleMouseUp = () => {
            document.removeEventListener('mousemove', handleMouseMove);
            document.removeEventListener('mouseup', handleMouseUp);
          };
          
          document.addEventListener('mousemove', handleMouseMove);
          document.addEventListener('mouseup', handleMouseUp);
        }}
      />
      
      {/* CLI panel */}
      <div style={{ height: `${cliHeight}px` }}>
        <CommandLine />
      </div>
    </div>
  );
}
```

## Styling Tips

### Custom Colors

Override the default green theme:

```css
.commandLine {
  --cli-text-color: rgb(0, 200, 255); /* Cyan */
  --cli-bg-color: rgba(0, 0, 0, 0.9);
  --cli-border-color: rgba(0, 200, 255, 0.3);
  --cli-error-color: rgb(255, 100, 100);
}
```

### Custom Font

```css
.commandLine {
  font-family: 'Fira Code', 'Monaco', 'Consolas', monospace;
  font-size: 13px;
}
```

### Transparent Background

```css
.commandLine {
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(10px);
}
```

## Store Integration

The CLI automatically integrates with the Zustand store. No additional setup required!

### Commands Affect Store

```bash
# These commands modify the store:
arcnet> select node-001        # Calls setSelectedNode()
arcnet> select node-001 --fly  # Calls setSelectedNode() + flyToNode()
arcnet> fly northAmerica       # Calls flyToPreset()
```

### Store Changes Reflect in CLI

When the store updates (e.g., new nodes added), the CLI commands will see the latest data:

```bash
arcnet> nodes  # Always shows current nodes from store
arcnet> status # Always shows current stats from store
```

## Keyboard Focus

The CLI automatically focuses the input field on mount. To manually focus:

```tsx
import { useRef, useEffect } from 'react';

function MyComponent() {
  const cliRef = useRef<HTMLDivElement>(null);
  
  useEffect(() => {
    // Focus CLI when component mounts
    const input = cliRef.current?.querySelector('input');
    input?.focus();
  }, []);
  
  return (
    <div ref={cliRef}>
      <CommandLine />
    </div>
  );
}
```

## Accessibility

The CLI is keyboard-accessible:
- Tab to focus input
- Arrow keys for history
- Enter to execute
- Escape to clear suggestions

## Performance Considerations

1. **Height**: Always provide a fixed height to prevent layout shifts
2. **History**: Limited to 100 commands automatically
3. **Output**: Auto-scrolls efficiently without re-rendering entire list
4. **Debouncing**: Consider debouncing if adding real-time features

## Testing Integration

```tsx
import { render, screen } from '@testing-library/react';
import { CommandLine } from '@/components/CommandLine';

test('CLI renders and accepts input', () => {
  render(<CommandLine />);
  const input = screen.getByRole('textbox');
  expect(input).toBeInTheDocument();
});
```

## Common Issues

### Issue: CLI not visible
**Solution:** Ensure parent has defined height:
```tsx
<div style={{ height: '300px' }}>
  <CommandLine />
</div>
```

### Issue: Commands not working
**Solution:** Ensure store is properly initialized with nodes/data

### Issue: Cursor not blinking
**Solution:** Check if CSS animations are enabled in browser

### Issue: History not working
**Solution:** Ensure store's `commandHistory` is accessible

## Advanced Usage

### Custom Commands

Add your own commands by extending `commands.ts`:

```typescript
// In commands.ts
function handleMyCommand(cmd: ParsedCommand): CommandOutput {
  // Your logic
  return { lines: ['Output'] };
}

// Register it
export function getCommandHandlers() {
  return {
    mycommand: handleMyCommand,
    // ... other commands
  };
}
```

### Command Aliases

```typescript
export function getCommandHandlers() {
  const handlers = {
    nodes: handleNodes,
    // ... other commands
  };
  
  // Add aliases
  return {
    ...handlers,
    ls: handlers.nodes,      // Alias: ls -> nodes
    n: handlers.nodes,       // Alias: n -> nodes
    s: handlers.status,      // Alias: s -> status
  };
}
```

## Best Practices

1. **Always provide height** to the CLI container
2. **Use fixed positioning** for bottom/side panels
3. **Consider mobile** - CLI works but may need smaller font
4. **Test keyboard navigation** - ensure all shortcuts work
5. **Monitor performance** - limit output lines if needed

## Example: Full Integration

```tsx
import { CommandLine } from '@/components/CommandLine';
import { Globe } from '@/components/Globe';
import { NodeDetail } from '@/components/NodeDetail';
import styles from './ConsoleLayout.module.css';

export function ConsoleLayout() {
  return (
    <div className={styles.layout}>
      {/* Header */}
      <header className={styles.header}>
        <h1>ArcNet Console</h1>
      </header>
      
      {/* Main content area */}
      <div className={styles.mainContent}>
        {/* Globe visualization */}
        <div className={styles.globe}>
          <Globe />
        </div>
        
        {/* Node detail panel */}
        <aside className={styles.sidebar}>
          <NodeDetail />
        </aside>
      </div>
      
      {/* CLI at bottom */}
      <footer className={styles.cli}>
        <CommandLine />
      </footer>
    </div>
  );
}
```

```css
/* ConsoleLayout.module.css */
.layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

.header {
  height: 60px;
  border-bottom: 1px solid rgba(0, 255, 100, 0.3);
}

.mainContent {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.globe {
  flex: 1;
}

.sidebar {
  width: 300px;
  border-left: 1px solid rgba(0, 255, 100, 0.3);
}

.cli {
  height: 250px;
  border-top: 1px solid rgba(0, 255, 100, 0.3);
}
```

This creates a complete console layout with the CLI integrated at the bottom!

