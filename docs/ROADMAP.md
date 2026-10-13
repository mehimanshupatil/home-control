# Roadmap

## Done
- Mac menu bar app and Android app with matching UI.
- MirAIe: cloud bootstrap, local UDP control with cloud fallback, Local network only mode, key-code backup and restore, model-driven More controls, info chips, redacted diagnostics.
- WiZ: discovery, on/off, brightness, white temperature, colour, grouped scenes with speed, Identify, local rename.

## Next (needs real-device diagnostics first)
1. **Confirm against a real AC:** status key names (humidity, filter, power, faults), swing value meaning, and whether the AC pushes status over UDP without a prior command.
2. **Timers:** `ACTimer` sends `[offTimer, onTimer]`. The official app has two builders (relative seconds and UTC epoch seconds), so which one the firmware expects must be tested. UI: "Turn off in…" and "Turn on at…".
3. **Local profiles:** user-defined presets (for example *Night*: 26°, Quiet, display off, swing auto) stored in the app and applied as a sequence of local commands. This replaces the cloud-only MirAIe action groups.
4. **Clean mode guard:** the official app disables swing while Clean runs; mirror that.
5. **Intesis / Comfort Cloud bridged ACs:** these use the `/tcontrol` MQTT topic, which isn't handled yet.

## Later
- WiZ fade-in/out and startup mode (needs `getUserConfig`/`setUserConfig` shapes confirmed on a bulb).
- Local schedules for lights.
- Mac: show the selected AC's temperature in the menu bar label.
- Android: home-screen widget.
- Power-consumption history (cloud only: `powerConsumption/homes/{homeId}`).

## Unverified assumptions
| Assumption | Where it matters |
|---|---|
| 10-digit mobile numbers need a `+91` prefix | Login (the official app prefixes the selected country code) |
| `expiresIn` is in seconds | Token refresh timing |
| AC devices are identified by `category` | Device filtering (falls back to showing all devices) |
| LAN key/IV are stable until the AC is re-paired | Key-code usefulness |
| WiZ scene grouping (White/Colors/Moving) | Scene menu only |
