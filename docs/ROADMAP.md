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

## Next
1. **Test on the real AC:** that a timer set from the app appears in `actm` and fires, and that profiles apply cleanly over MQTT.
2. **Try local control (experimental):** report whether the AC answers `DISC_REQ` even though model 130185 lacks `udpControl`.
3. **Sleep profile (`acsp`):** status shows `"none"`; the payload needs a cloud profile id (`pid`), so it's not built.
4. **Intesis / Comfort Cloud bridged ACs:** these use the `/tcontrol` MQTT topic, which isn't handled yet.
5. **ECO sets 26°:** the official app sets 26° when ECO turns on; decide whether to mirror it.

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
