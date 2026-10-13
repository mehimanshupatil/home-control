# Roadmap

## Done
- Mac menu bar app and Android app with matching UI.
- MirAIe: cloud bootstrap, local UDP control with cloud fallback, Local network only mode, key-code backup and restore, model-driven More controls, info chips, redacted diagnostics.
- WiZ: discovery, on/off, brightness, white temperature, colour, grouped scenes with speed, Identify, local rename.
- Mac and Android, built against real model 130185 (PROTOCOL.md §1.8):
  - Mode, fan, temperature range/step, Convertible and Display choices come from the model.
  - Real status chips: Wi-Fi signal, Clean filter, filter dust, errors and warnings.
  - Timers (relative seconds, status in UTC) with presets, custom duration and cancel.
  - Swing is locked while Clean runs.
  - Local profiles: save, apply and delete.
  - Experimental "Try local control" for models without `udpControl`; diagnostics report `lanKeyAvailable` and `lastLocalDiscovery`.
- Both apps, from the full feature inventory (docs/FEATURES.md):
  - Official side effects: power-on 24°, ECO 26°, Powerful/JetStream turn Convertible off, JetStream/Turbo swing 1, profile reset (PROTOCOL.md §1.14).
  - Energy chart (Day / Week / Month kWh), cloud sleep profiles (create, edit, delete, start, stop) and their schedules (rms rules, pause/resume).
  - Filter health bar and reset (time-based and dust-based), PM2.5 band chip, JetStream / EcoNavi / Humidifier / Laundry toggles, nanoe GA cycle.
  - REST control fallback when MQTT is down; rename and move-room; firmware update check; notification history with unread count.
  - WiZ: `pulse` identify (blink fallback), scenes 33 and 35, local rooms with group power / brightness / scene, saved light presets.

## Next
1. **Test on the real AC:** timer in `actm`, profiles over MQTT, sleep-profile start/stop and schedule, energy data, filter reset, REST fallback for non-power keys.
2. **Try local control (experimental):** report whether the AC answers `DISC_REQ` even though model 130185 lacks `udpControl`.
3. **Intesis / Comfort Cloud bridged ACs:** these use the `/tcontrol` MQTT topic, which isn't handled yet.

## Later
- WiZ fade-in/out and startup mode (needs `getUserConfig`/`setUserConfig` shapes confirmed on a bulb).
- Local schedules for lights.
- Mac: show the selected AC's temperature in the menu bar label.
- Android: home-screen widget.
- Proximity (geofence) prompts, cloud scenes/routines, AI Mode (see docs/FEATURES.md "Later").

## Unverified assumptions
| Assumption | Where it matters |
|---|---|
| 10-digit mobile numbers need a `+91` prefix | Login (the official app prefixes the selected country code) |
| `expiresIn` is in seconds | Token refresh timing |
| AC devices are identified by `category` | Device filtering (falls back to showing all devices) |
| LAN key/IV are stable until the AC is re-paired | Key-code usefulness |
| WiZ scene grouping (White/Colors/Moving) | Scene menu only |
| REST control accepts keys other than `ps` (only power is seen in the app) | Cloud fallback when MQTT is down |
| WiZ `pulse` params `delta: -60, duration: 800` and a `{"result":{"success":true}}` reply | Identify (falls back to blinking) |
| Energy `power` values are kWh per bucket | Energy chart totals |
