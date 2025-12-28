# CLI Navigation Guide

Quick reference for navigating between the main console and CLI demo page.

## Visual Layout

### Main Console View
```
┌─────────────────────────────────────────────────────────────┐
│  ARCNET Header                    [⌨️ CLI Demo] [●] [Clock] │ ← Click here
├─────────────────────────────────────────────────────────────┤
│                    │                                         │
│                    │  Resource Panel                         │
│      Globe         │  ─────────────                          │
│   Visualization    │  Node Details                           │
│                    │                                         │
├─────────────────────────────────────────────────────────────┤
│  Event Log                                                   │
├─────────────────────────────────────────────────────────────┤
│  arcnet> _                                                   │ ← CLI here
│  Command Line Interface                                      │
└─────────────────────────────────────────────────────────────┘
```

### CLI Demo Page
```
┌─────────────────────────────────────────────────────────────┐
│  [← Back to Console]  ⌨️ ArcNet CLI Demo    [Hide/Show Help]│ ← Click here
├──────────────────┬──────────────────────────────────────────┤
│                  │                                           │
│  Help Panel      │  Terminal                                │
│  ─────────       │  ─────────                               │
│  • Examples      │  arcnet> _                               │
│  • Shortcuts     │                                           │
│  • Tips          │  Full-screen CLI                         │
│  • Sequences     │                                           │
│                  │                                           │
└──────────────────┴──────────────────────────────────────────┘
```

## Navigation Buttons

### From Main Console to CLI Demo

**Location:** Top-right corner of the header

**Button:** `⌨️ CLI Demo`

**Appearance:**
- Green text on dark background
- Keyboard emoji (⌨️)
- Hover effect: glows green

**Action:** Click to open the CLI Demo page

**Alternative:** Navigate to `http://localhost:3000/#cli-demo`

---

### From CLI Demo to Main Console

**Location:** Top-left corner of the header

**Button:** `← Back to Console`

**Appearance:**
- Green text on dark background
- Left arrow (←)
- Hover effect: glows green and slides left

**Action:** Click to return to the main console

**Alternative:** Navigate to `http://localhost:3000/`

---

## Quick Access

### URLs

| View | URL |
|------|-----|
| Main Console | `http://localhost:3000/` |
| CLI Demo | `http://localhost:3000/#cli-demo` |

### Keyboard Navigation

You can also use browser navigation:
- **Back:** Browser back button or `Alt+←` (Windows) / `Cmd+[` (Mac)
- **Forward:** Browser forward button or `Alt+→` (Windows) / `Cmd+]` (Mac)

## Button Locations Summary

```
Main Console Header:
┌────────────────────────────────────────────────┐
│ ARCNET Logo | Stats | Stats | [⌨️ CLI Demo] │
└────────────────────────────────────────────────┘
                                    ↑
                              Click here to go to CLI Demo

CLI Demo Header:
┌────────────────────────────────────────────────┐
│ [← Back] | ⌨️ CLI Demo Title | [Hide/Show Help]│
└────────────────────────────────────────────────┘
   ↑
   Click here to go back to Main Console
```

## Tips

1. **Bookmark both pages** for quick access
2. **Use browser tabs** to keep both open
3. **Browser history** works - use back/forward buttons
4. **Hash navigation** - URLs use `#cli-demo` for routing
5. **Hot reload** - Changes update automatically in dev mode

## Troubleshooting

### Button not visible
- **Main Console:** Check top-right of header, next to clock
- **CLI Demo:** Check top-left of header, before title
- **Refresh page** if buttons don't appear

### Navigation not working
- **Check URL:** Should be `localhost:3000/` or `localhost:3000/#cli-demo`
- **Clear cache:** Hard refresh with `Ctrl+Shift+R` (Windows) or `Cmd+Shift+R` (Mac)
- **Check console:** Open browser DevTools for errors

### Page doesn't load
- **Dev server running?** Check terminal for `VITE v5.4.21 ready`
- **Correct port?** Should be port 3000
- **Try direct URL:** Type the full URL in address bar

## Examples

### Workflow 1: Learn CLI, Then Use It
1. Open main console: `http://localhost:3000/`
2. Click **"⌨️ CLI Demo"** button
3. Read help panel, try examples
4. Click **"← Back to Console"**
5. Use CLI at bottom of main console

### Workflow 2: Quick Reference
1. Open both in separate tabs:
   - Tab 1: `http://localhost:3000/` (Main Console)
   - Tab 2: `http://localhost:3000/#cli-demo` (CLI Demo)
2. Switch between tabs as needed
3. Reference examples in CLI Demo
4. Execute commands in Main Console

### Workflow 3: Testing Commands
1. Open CLI Demo: `http://localhost:3000/#cli-demo`
2. Test commands in full-screen terminal
3. See results immediately
4. Switch to main console to see effects on globe

## Visual Indicators

### Main Console Button
```
┌─────────────────┐
│  ⌨️ CLI Demo   │  ← Keyboard emoji
└─────────────────┘
     Green glow on hover
```

### CLI Demo Button
```
┌──────────────────────┐
│  ← Back to Console  │  ← Left arrow
└──────────────────────┘
     Slides left on hover
```

Both buttons:
- ✅ Green terminal color scheme
- ✅ Hover effects
- ✅ Clear labels
- ✅ Responsive (work on mobile)

## Mobile Navigation

On mobile devices:
- Buttons may wrap to new line
- Touch-friendly size
- Same functionality
- Responsive layout

```
Mobile Header (Main Console):
┌──────────────────┐
│ ARCNET Logo      │
│ [⌨️ CLI Demo]   │
└──────────────────┘

Mobile Header (CLI Demo):
┌──────────────────┐
│ [← Back]         │
│ ⌨️ CLI Demo     │
└──────────────────┘
```

## Summary

**Main Console → CLI Demo:**
- Click `⌨️ CLI Demo` (top-right)

**CLI Demo → Main Console:**
- Click `← Back to Console` (top-left)

**Both views have CLI:**
- Main Console: Bottom of screen (integrated)
- CLI Demo: Full-screen (with help panel)

Happy navigating! 🚀

