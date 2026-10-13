# UI specification

Shared design for the Mac menu bar app and the Android app. Both apps implement this structure with the same wording; platform idioms (menus, sheets, long-press vs right-click) may differ. Visual tokens live in `mac/Sources/HomeBar/Theme.swift` and `android/.../ui/Theme.kt`.

## Principles
1. **Glanceable card, deep pages.** A card shows what you change daily; everything else is one tap away on a detail page. No card is taller than ~1.2 panel heights.
2. **Show state, not chrome.** Status appears only when it matters (an active timer, a filter alert, an error). Normal states stay quiet.
3. **One action style per role.** Option tiles select a value; action buttons open a page; pills report state. Never reuse one style for another role.
4. **Cloud is labelled.** Anything that needs the MirAIe cloud shows a small cloud glyph, and an explanation instead of an error when it can't run.

## Tokens (unchanged)
- Card radius 18 = tile radius 6 + padding 12. Pills are capsules. Soft layered shadows, no depth borders.
- Mode tints: Cool blue `#0A84FF`, Heat orange `#FF9500`, Dry teal `#30B0C7`, Fan green `#34C759`, Auto purple `#AF52DE`.
- Press: scale 0.96, 150 ms ease-out, never when disabled.
- Frequent interactions (tabs, mode, fan, toggles, sliders) change instantly.
- Page push: enter 300 ms ease-out with opacity + 12 pt offset from the trailing edge; back: 150 ms. Reduced motion / animations off: opacity only.
- Numbers use rounded, monospaced digits.

## Panel / screen
- **Header:** "Home", one-line summary, Settings (gear) menu, Quit (Mac) / overflow (Android).
- **Tabs:** Climate · Lights (pill segmented control).
- Mac panel: 380 pt wide, 540 pt tall content area.

## Climate tab

### AC card (compact)
```
┌───────────────────────────────────────────┐
│ Bedroom AC                         (⏻)    │  name · power button (mode-tinted when on)
│ Bedroom · ☁ Cloud · ▂▄▆                   │  room · transport badge · Wi-Fi bars icon
│                                           │
│ 24°                        (−)  (+)       │  target temp hero; "Off" caption when off
│ Room 29°                                  │
│                                           │
│ [Auto][Cool][Dry][Fan]                    │  mode tiles (model values)
│ ✣ [Auto][Quiet][Low][Med][High]           │  fan chips
│                                           │
│ ⏱ Off at 23:40 ✕   ☾ Cool night ✕   ⚠ …   │  status pills — only when active
│ ◉ Night  ◉ Quick cool                     │  profile chips — only if any saved
│                                           │
│ [⏱ Timer][☾ Sleep][≋ Air][ϟ Energy][⋯ More]│  action bar → detail pages
└───────────────────────────────────────────┘
```
- Status pills (only when present): active timer(s), running sleep profile, Clean running, filter needs cleaning, errors, warnings, PM2.5 when the model reports it.
- Action bar: 5 equal buttons, icon above a short label, neutral fill, visually distinct from option tiles (no selected state; a small dot shows when that page has something active, e.g. a timer set). Cloud-only pages carry a cloud glyph.

### Detail pages
Each page has a back bar: `‹ Back` · page title · AC name as subtitle. Content is grouped in cards.

| Page | Contents |
|---|---|
| **Timer** | Turn on / Turn off selector, presets 30 min · 1 h · 2 h · 4 h · 8 h, custom duration, active timers with Cancel |
| **Sleep** ☁ | Running profile with Stop; profile list with curve, Start, Edit, Schedule, Pause/Resume, Delete; New profile (max 3 + Regular); editor |
| **Air** | Vertical swing, Horizontal swing (locked while Clean runs), Display, Convertible / capacity, feature toggles grid (Powerful, ECO, Clean, nanoe, JetStream, …) |
| **Energy** ☁ | Day / Week / Month, total kWh, bar chart |
| **More** | Filter health + Reset (☁ for time-based), Profiles (save current / delete), AC settings ☁ (rename, room, firmware check), Notifications ☁, Diagnostics, LAN keys |

## Lights tab
- **Room sections:** header row with room name, "2 of 3 on", room power button; room brightness slider and scene menu.
- **Bulb tiles:** two-column grid. Tile = glowing icon circle (tap toggles) + name + status ("80% · 2700K"). Tile tint follows the bulb colour when on. Tap the tile body (not the icon) to open the bulb page.
- **Bulb page:** brightness, white temperature, colour swatches + custom, scene menu (grouped) + speed, presets, Identify, Rename, Room, IP.
- **Unassigned bulbs** appear in an "Other lights" section. "Manage rooms" lives in the Settings menu and as a row at the end of the list.
- Quick actions All on / All off stay at the top.

## Empty, loading, error
- Empty states: icon, title, one sentence, one action.
- Loading: inline spinner in the section header, never a blocking overlay.
- Errors: inline banner in the page or card it belongs to.
