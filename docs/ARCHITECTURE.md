# Architecture

Two independent native apps implement the same features against the same protocols ([PROTOCOL.md](../PROTOCOL.md)). There is no shared code and no backend: each app talks directly to the devices (and, when needed, to the MirAIe cloud).

```
                ┌──────────────────────────┐        ┌───────────────────────────┐
                │ mac/  HomeBar (SwiftUI)  │        │ android/  Home Control    │
                │ menu bar, macOS 14+      │        │ Compose, Android 8+       │
                └─────────────┬────────────┘        └─────────────┬─────────────┘
                              │ same protocols, same key-code format
      ┌───────────────────────┼─────────────────────────────┬─────┴───────────────┐
      ▼                       ▼                             ▼                     ▼
 MirAIe AC (LAN)        MirAIe cloud                  MirAIe cloud           WiZ bulbs (LAN)
 UDP 5001, AES-CBC      HTTPS REST                    MQTT/TLS 8883          UDP 38899, JSON
 control + status       login, homes, model,          control + status       getPilot / setPilot
                        LAN keys (one-time)           (fallback only)
```

## AC: transport selection

1. **Bootstrap (cloud, once):** login → `homeManagement/homes` → per AC: device model (capabilities, `udpControl`) and device details (MAC, IP, `udpControlKey`, `udpcontrolIV`). Everything is cached encrypted.
2. **Discovery (LAN):** bind UDP 5001, send plaintext `DISC_REQ_<MAC>` to the last known IP and the subnet broadcast address, and wait ~3 s for an encrypted `DISC_RSP_<mac>`.
3. **Pick per AC:** answered → **Local**. Otherwise → **Cloud** (MQTT), or **Unreachable** when *Local network only* is on.
4. **Re-check:** discovery is repeated periodically and on refresh, so a changed DHCP address is found again.
5. **Key-code import** skips steps 1–2's cloud part: devices and LAN keys come from the code, and local-only is switched on.

Commands use one envelope on both paths: `{"<key>": value, "ki": n, "cnt": "an", "sid": "1"}`. `<key>` comes from the model (`attributes[0].key`), with defaults as fallback. `ki` is the capability index on MQTT and always `1` on UDP (matching the official app).

Status from every source (UDP packets, MQTT `<topic>/status`, REST `mobile/status`) is **merged** into one per-AC dictionary that drives both the core controls and the model-driven "More controls" and info chips.

## Model-driven controls

The device model's `controlCapabilities` / `statusCapabilitiesV2` are parsed into capability descriptors: name, wire key, `ki`, kind (toggle / choice / int-choice / range / status) and allowed values. The UI renders only what the model lists. Mappings that aren't in the model (Convertible ints, ACGenMode 40→5 … 110→1, Display on/night/off) are taken from the decompiled enums; see PROTOCOL.md §1.7.

## Timers and profiles (Mac; Android pending)

- **Timer** (`ACTimer`, shown only when the model lists it): the app sends `[offSeconds, onSeconds]` relative to now, with -1 for unset. It keeps the other slot as its remaining time, read from the status `actm` (UTC epoch seconds). Presets and the custom stepper use the model range (10–1440 min, step 10).
- **Profiles** are stored per AC in UserDefaults (`acProfiles-<deviceId>`). Applying one sends only the settings that differ, as separate commands about 300 ms apart: power first when turning on, and only power when the profile turns the AC off.
- **Try local control (experimental):** off by default. When on, the app fetches LAN keys and runs discovery even for models without `udpControl`. Diagnostics record `lanKeyAvailable` and `lastLocalDiscovery`.

## Cloud features (Mac; Android pending)

`ACCloud.swift` holds the cloud-only features and their pure payload builders (checked by the snapshot harness):
energy windows (`EnergyWindow`, PROTOCOL.md §1.9), sleep profiles and schedules (`SleepProfile`, `SleepSchedule`, §1.10–1.11),
filter reset (§1.12), rename/move, firmware check and notifications (§1.13). `ACCloudViews.swift` renders them as panels on the
AC card (Energy, Sleep, Filter, Settings) plus a notifications card. Every cloud panel shows a "MirAIe cloud" caption; when
there's no session, or Local network only is on, it shows `ACStore.cloudBlocker` instead of erroring.

Commands go through `ACStore.sendFields`, which adds the envelope per transport: LAN (`ki 1`), MQTT (`ki n`, `cnt an`), or, when
MQTT isn't connected, REST `deviceManagement/control` (`cnt gva`, `sid 11`). Toggles go through `setToggle`, which reproduces the
official side effects (§1.14).

## WiZ

Rooms (`LightGroup`) and presets (`LightPreset`, one `PilotState` per bulb) are stored locally in UserDefaults (`wizGroups`, `wizPresets`); group actions fan out `setPilot` per bulb. Identify sends `pulse` and falls back to an off/on blink when the bulb doesn't confirm within 1 s.

Stateless UDP JSON-RPC. Discovery broadcasts `getPilot` and collects replies keyed by MAC; control is `setPilot` to the bulb's IP with retries. Names are stored locally (bulbs don't carry the WiZ app's names). Identify blinks the bulb using `setPilot` only.

## Storage

| Data | macOS | Android |
|---|---|---|
| MirAIe login, password, tokens | Keychain (`in.canvs.homebar`) | EncryptedSharedPreferences `miraie_secure` |
| Per-AC LAN key/IV/MAC/IP | Keychain `local-<deviceId>` | Inside the encrypted device cache |
| Device list, model, last status | UserDefaults `acDevices` | Encrypted device cache |
| Local-only flag, tab, bulb names | UserDefaults | Plain SharedPreferences |

## Source map

### mac/ (Swift Package `HomeBar`, no third-party dependencies)
| File | Role |
|---|---|
| `HomeBarApp.swift` | `MenuBarExtra` entry point, header, settings menu, tab switcher |
| `ACStore.swift` | AC state owner: session, cloud bootstrap, transport choice, merged status, key codes, diagnostics |
| `MirAIeAPI.swift` | REST client, token refresh, re-login |
| `MQTTClient.swift` | Hand-written MQTT 3.1.1 over TLS (Network.framework) |
| `LocalACTransport.swift` | UDP 5001 discovery, AES-CBC (CommonCrypto), status receive |
| `Capabilities.swift`, `JSONValue.swift` | Model capability parsing; Codable JSON for raw model and status |
| `ACViews.swift`, `ACExtras.swift` | AC cards, login, key-code import, keys card, More controls, info chips, diagnostics |
| `LightsStore.swift`, `WizUDP.swift`, `LightsViews.swift` | WiZ state, UDP JSON-RPC, bulb cards, local rooms and presets |
| `Theme.swift` | Design tokens and shared components (card, tiles, pills, press style, transitions) |
| `Keychain.swift`, `StateCompat.swift` | Keychain wrapper; `@ViewState` alias (see AGENTS.md) |
| `ACCloud.swift`, `ACCloudViews.swift` | Cloud features (energy, sleep profiles + schedules, filter, rename/move, firmware, notifications) and their card panels |
| `Snapshot.swift` | DEBUG-only renderer to PNG plus self-checks (key round trip, redaction, model parsing, timer, sleep/rule/energy payloads, side effects). Runs with sample data and never touches the real session, cloud or LAN. |

### android/ (single module `app`, package `in.canvs.homecontrol`)
| File | Role |
|---|---|
| `HomeControlApp.kt`, `MainActivity.kt` | App-scoped repositories; activity (debug `--ez demo true` seeding) |
| `miraie/AcRepository.kt` | AC state owner (same responsibilities as `ACStore.swift`), exposes `StateFlow<AcUiState>` |
| `miraie/MiraieApi.kt`, `MiraieMqtt.kt`, `LocalAcTransport.kt` | REST (OkHttp), MQTT (Paho), UDP 5001 (javax.crypto) |
| `miraie/Models.kt`, `Capabilities.kt`, `Diagnostics.kt` | Data types, model capability parsing, redacted diagnostics |
| `wiz/WizClient.kt`, `LightsRepository.kt` | WiZ UDP and state |
| `data/SecureStore.kt` | Encrypted + plain preferences |
| `ui/*` | Compose screens; `Theme.kt` holds tokens and shared components |
| `AcTileService.kt` | Quick Settings tile (power of the first AC) |
| `app/src/test/.../DiagnosticsTest.kt` | JVM tests: redaction, capability parsing |

## UI system (both apps)

Shared visual rules so the apps look alike:
- Cards: radius 18 = tile radius 6 + padding 12 (concentric). Elevation through soft layered shadows, not borders.
- Mode colours: Cool blue, Heat orange, Dry teal, Fan green/mint, Auto purple. The card is tinted by mode when the AC is on; selected tiles go neutral when it's off.
- Press feedback: scale 0.96, 150 ms ease-out; never on disabled controls; skipped under Reduce Motion (macOS) or when system animations are off (Android).
- Frequent interactions (tabs, mode, fan) change instantly. Expand: 300 ms enter with a 12 pt rise, 150 ms exit.
- State is never shown by motion alone: fill, icon and label change together.
- Status pill text is darkened in light mode for contrast.
