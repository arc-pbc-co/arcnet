# How to Access the CLI

The ArcNet CLI is now integrated into the console and accessible in two ways:

## 1. Main Console (Integrated CLI)

The CLI is integrated at the bottom of the main console layout.

**Access:** 
- Navigate to: `http://localhost:3000/`
- The CLI is visible at the bottom of the screen
- Type commands directly in the terminal area

**Features:**
- Full CLI functionality
- Integrated with Globe visualization
- Commands affect the main console state
- Select nodes, fly camera, view stats, etc.

## 2. CLI Demo Page (Standalone)

A dedicated demo page showcasing the CLI with examples and help.

**Access:**
- Click the **"⌨️ CLI Demo"** button in the header, OR
- Navigate to: `http://localhost:3000/#cli-demo`

**Features:**
- Full-screen CLI interface
- Interactive help panel with:
  - Example commands
  - Keyboard shortcuts
  - Quick tips
  - Command sequences
- Perfect for learning and testing

## Quick Start

### From Main Console

1. Start the dev server:
   ```bash
   cd arcnet-console
   npm run dev
   ```

2. Open browser to `http://localhost:3000/`

3. Look at the bottom of the screen for the CLI

4. Try these commands:
   ```bash
   arcnet> help
   arcnet> status
   arcnet> nodes
   ```

### From CLI Demo

1. Click **"⌨️ CLI Demo"** in the header, OR navigate to `http://localhost:3000/#cli-demo`

2. The help panel on the left shows example commands

3. Try the example commands in the terminal

4. Use keyboard shortcuts:
   - `↑/↓` - Navigate history
   - `Tab` - Autocomplete
   - `Enter` - Execute

## Navigation

### Main Console → CLI Demo
- Click **"⌨️ CLI Demo"** button in the header (top-right)
- Or navigate directly to `http://localhost:3000/#cli-demo`

### CLI Demo → Main Console
- Click **"← Back to Console"** button in the header (top-left)
- Or navigate directly to `http://localhost:3000/`

## Example Commands

### System Status
```bash
arcnet> status
arcnet> stats
arcnet> events
```

### Node Management
```bash
arcnet> nodes
arcnet> nodes --status=online
arcnet> nodes --energy=solar
arcnet> select node-001 --fly
```

### Navigation
```bash
arcnet> fly northAmerica
arcnet> fly global
arcnet> fly node-042
```

### Monitoring
```bash
arcnet> jobs
arcnet> jobs --status=running
arcnet> events --limit=20
arcnet> history
```

### Analysis
```bash
arcnet> route node-001 node-042
arcnet> stats --geozone=CAISO
```

## Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `↑` | Previous command |
| `↓` | Next command |
| `Tab` | Autocomplete |
| `Esc` | Clear suggestions |
| `Enter` | Execute command |

## Features

### ✅ In Both Views
- Command parsing with flags
- Command history (↑/↓)
- Tab autocomplete
- Blinking cursor
- Error highlighting
- Store integration

### ✅ CLI Demo Only
- Help panel with examples
- Keyboard shortcuts reference
- Quick tips
- Command sequences
- Full-screen terminal

## Documentation

- **User Guide:** [CLI_GUIDE.md](./CLI_GUIDE.md)
- **Integration Guide:** [CLI_INTEGRATION.md](./CLI_INTEGRATION.md)
- **Implementation Summary:** [CLI_IMPLEMENTATION_SUMMARY.md](./CLI_IMPLEMENTATION_SUMMARY.md)

## Troubleshooting

### CLI not visible in main console
- Make sure dev server is running: `npm run dev`
- Check browser console for errors
- Refresh the page

### Commands not working
- Ensure store is initialized with data
- Check command syntax: `help`
- Look for error messages in red

### Demo page not loading
- Navigate to: `http://localhost:3000/#cli-demo`
- Or click the "⌨️ CLI Demo" button in header
- Refresh if needed

## Tips

1. **Start with `help`** - Shows all available commands
2. **Use Tab** - Autocomplete command names
3. **Use ↑/↓** - Navigate command history
4. **Try filters** - `nodes --status=online --energy=solar`
5. **Fly to nodes** - `select node-001 --fly`
6. **Clear output** - `clear` command

## Next Steps

1. Try the example commands
2. Explore the CLI Demo page
3. Read the full [CLI_GUIDE.md](./CLI_GUIDE.md)
4. Integrate CLI into your workflow

Enjoy the ArcNet CLI! 🎉

