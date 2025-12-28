# ARCNet Console - Feature Guide

## Globe Visualization Features

### Geographic Map Layers
The globe displays detailed geographic boundaries:

**Country Boundaries**
- Thin green lines showing all country borders worldwide
- Helps identify which country each node is in
- Provides global geographic context

**US State Boundaries**
- Thicker, brighter green lines showing all US state borders
- More prominent than country borders
- Critical for US-focused analysis (default view)
- Makes regional analysis easier

### Animated Traffic Arcs
The globe now displays animated arcs showing real-time data flow:

**Inference Arcs (Cyan)**
- Pulse animation shows active inference requests
- Width indicates priority (critical/normal/background)
- Smooth fade-in when created, fade-out when completed
- 2-second lifetime with smooth transitions

**HPC Transfer Arcs (Purple)**
- Show data transfers to ORNL Frontier supercomputer
- Width based on dataset size
- Persistent until transfer completes
- Fade-in animation on creation

### Node Visualization
- **Color by Energy Source**:
  - Yellow: Solar-powered nodes
  - Orange: Grid-powered nodes
  - Green: Battery-powered nodes
- **Size by GPU Count**: Larger dots = more GPUs
- **Opacity by Status**: Faded nodes are stale/offline
- **Selection Highlight**: Green border on selected node

### Interactive Controls
- **View Presets**: Quick navigation to global/regional views
- **Click Nodes**: Select and view detailed information
- **Hover Tooltips**: See node stats on hover
- **Traffic Counter**: Real-time count of active arcs

## EventLog Features

### Filter Panel (Toggle with ⚙ button)

#### Search
```
┌─────────────────────────────────────┐
│ Search events...                    │
└─────────────────────────────────────┘
```
- Search by message text, node ID, or event type
- Case-insensitive
- Real-time filtering

#### Type Filters
```
[INF (45)] [HPC (12)] [NODE (8)] [SYS (23)]
```
- Click to toggle filter by event type
- Shows count for each type
- Multiple types can be selected
- Active filters highlighted in green

#### Severity Filters
```
[INFO (67)] [SUCCESS (12)] [WARN (5)] [ERROR (2)]
```
- Filter by severity level
- Color-coded buttons:
  - INFO: Cyan
  - SUCCESS: Green
  - WARN: Amber
  - ERROR: Red
- Shows count for each severity

### Event Display

#### Standard Event
```
[12:34:56] INF Request completed: 89ms latency    node-abc
```

#### Expandable Event (click to expand)
```
[12:34:56] HPC Transfer initiated: 450GB dataset  ▶
```

#### Expanded Event
```
[12:34:56] HPC Transfer initiated: 450GB dataset  ▼
  │ datasetSize:    450
  │ destination:    ORNL
  │ transferRate:   125MB/s
  │ estimatedTime:  3600s
```

### Controls

**Header Actions**
- `[⚙]` - Toggle filter panel
- `[⏸]` - Pause event stream (changes to `[▶]` when paused)
- `[↓]` - Scroll to bottom (appears when scrolled up)

**Event Counter**
```
45/86  ← Shows filtered/total events
```

**Auto-scroll Indicator**
When you scroll up manually, an indicator appears:
```
┌─────────────────────────────┐
│ New events below      [↓]   │
└─────────────────────────────┘
```

## Keyboard Shortcuts (Globe)

- `1` - Global view
- `2` - North America view
- `3` - Europe view
- `4` - Asia view
- `o` - ORNL view
- `Esc` - Deselect node

## Performance Features

### Globe
- Optimized rendering for 1000+ nodes
- Efficient arc lifecycle management
- Memoized layer creation
- Smart update triggers

### EventLog
- Displays up to 100 filtered events
- Memoized filtering and statistics
- Efficient Set-based filter state
- Smooth scrolling with auto-scroll detection

## Tips & Tricks

### Globe
1. **Watch Traffic Flow**: The pulsing arcs show the "heartbeat" of the network
2. **Identify Hotspots**: Nodes with many arcs are handling high traffic
3. **Monitor Energy**: Yellow nodes are solar-powered (green computing!)
4. **Track HPC Jobs**: Purple arcs show large training jobs going to ORNL

### EventLog
1. **Quick Filter**: Use type filters to focus on specific event categories
2. **Find Errors**: Click ERROR severity filter to see only problems
3. **Search Nodes**: Type a node ID to see all events for that node
4. **Investigate Details**: Click events with ▶ to see full details
5. **Pause for Analysis**: Hit pause to stop new events while investigating
6. **Clear Filters**: Use "Clear Filters" button to reset all filters at once

## Color Legend

### Map Layers
- 🟢 Thin Green Lines: Country boundaries
- 🟢 Thick Bright Green Lines: US state boundaries

### Node Colors
- 🟡 Yellow: Solar-powered
- 🟠 Orange: Grid-powered
- 🟢 Green: Battery-powered

### Arc Colors
- 🔵 Cyan: Inference requests
- 🟣 Purple: HPC transfers

### Event Severity
- 🔵 Cyan: Info/System events
- 🟢 Green: Success/Inference events
- 🟡 Amber: Warnings
- 🔴 Red: Errors

## Example Workflows

### Monitor System Health
1. Open filter panel
2. Select WARN and ERROR severities
3. Watch for any issues
4. Click events to see details

### Track Specific Node
1. Click node on globe
2. Note the node ID
3. Type node ID in EventLog search
4. See all events for that node

### Analyze Traffic Patterns
1. Watch arc animations on globe
2. Note traffic counter values
3. Filter EventLog to INF type
4. Correlate visual and log data

### Investigate HPC Jobs
1. Filter EventLog to HPC type
2. Click events to see dataset sizes
3. Watch purple arcs on globe
4. Monitor transfer progress

