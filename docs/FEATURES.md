# Feature inventory

Complete list of user-facing features in the vendor apps, compared with ours. Built from a full pass over the decompiled MirAIe app (screens + Retrofit APIs) and the WiZ app's strings, routes and bundled assets. Keep this file current: **every feature area starts here** (see AGENTS.md).

Legend: **Have** = in both our apps · **Build** = planned in the current round · **Later** = feasible, not scheduled · **Skip** = out of scope (reason given).
"Device" features are commands to the AC/bulb (LAN or MQTT). "Cloud" features need the vendor's servers.

## Panasonic MirAIe (air conditioner)

"Your AC" = model 130185 (from diagnostics; PROTOCOL.md §1.8). Model-driven controls appear only when the model lists them.

| Feature | Mechanism | Device / Cloud | Your AC | Status |
|---|---|---|---|---|
| Power, mode, temperature, fan | `ps` `acmd` `actmp` `acfs` | Device | ✓ | Have |
| Room temperature | status `rmtmp` | Device | ✓ | Have |
| Swing V/H, display, ECO, Powerful, Clean, Beep, Convertible | model capabilities | Device | ✓ | Have |
| nanoe G/X, ACGenMode, Turbo, Ionizer | model capabilities | Device | – | Have (model-driven) |
| On/off timer | `actm` `[off,on]` relative s | Device | ✓ | Have |
| Wi-Fi signal, filter-clean alert, errors/warnings | status keys | Device | ✓ | Have |
| Local profiles (our own presets) | sequence of commands | Device | ✓ | Have |
| LAN control / MQTT fallback / diagnostics | PROTOCOL.md §1.5–1.6 | Both | cloud only | Have |
| **Official side effects** (power-on raises temp < 24 to 24; ECO sets 26; Powerful turns Convertible off; JetStream sets swing 1) | client logic in `ACDevice` | Device | ✓ | **Build** |
| **Energy history chart** (daily / weekly / monthly kWh) | `GET powerConsumption/devices/{id}` | Cloud | ✓ (`PowerConsumption`) | **Build** |
| **Sleep profiles** (hourly temperature curves, max 3 + default, optional off at end) | CRUD `homeManagement/homes/{h}/spaces/{s}/devices/{d}/sleepProfile`; activate `{"acsp":"on","ki":12,"pid":id,"temps":[…]}` | Cloud store + Device | ✓ (`acsp`) | **Build** |
| **Sleep profile schedule** (weekday + time) | `rms/homes/{h}/rules` (Timer trigger) | Cloud | ✓ | **Build** |
| **Filter reset** | time-based: `PUT deviceManagement/devices/{id}/filterDustLevelInfo`; dust-based: `FilterCalibration` command | Cloud / Device | ✓ time-based (`acfh`) | **Build** |
| **Filter health** (operating hours vs 100/200/500 h thresholds) | status `totalOperatingHours` + `acfh` attribute ranges | Device | ✓ | **Build** |
| **PM2.5 / AQI readout** | status `PM25Sensor`, bands 0-30/31-60/61-90/91-120/121-250/>250 | Device | – | **Build** (model-driven) |
| **More toggles**: JetStream, EcoNavi, EconomySensor, MotionSensor, Humidifier, Laundry; nanoeGA (off→on→auto) | model capabilities | Device | – | **Build** (model-driven) |
| **REST control fallback** | `POST deviceManagement/control {deviceId, control_params}` | Cloud | ✓ | **Build** (fallback when MQTT is down) |
| **Rename AC / move room** | `PUT …/devices/{d} {deviceName}`; `POST …/spaces/{s}/devices/{d} {newSpaceId}` | Cloud | ✓ | **Build** |
| **Firmware version + update check** | `GET fota/firmware/deviceId/{id}/download?currentVersion=` | Cloud | ✓ | **Build** (check only) |
| Firmware update trigger | MQTT `<topic>/fcontrol {crit,ver,ts}` | Cloud+Device | ✓ | Skip — flashing firmware from an unofficial app is risky; use the MirAIe app |
| **Notification history** (alerts, sleep-profile, rules) | `GET notificationmanager/log`, `/unexpired/count` | Cloud | ✓ | **Build** (read-only list) |
| Proximity (geofence) on/off prompts | phone location + REST control; gated by `ProximityNotification` | Cloud + phone | ✓ | Later (needs location permission design, esp. macOS) |
| Cloud scenes ("actionGroups") + scheduled routines | `homeManagement/homes/{h}/actionGroups`, `rms` rules | Cloud | ✓ | Later — our local profiles cover most of it |
| AI Mode per room | MQTT `{metaTopic}/{spaceId}/aicontrol {"ai":"on/off"}` | Cloud MQTT | ? | Later (unknown if your home supports it) |
| Push notifications | FCM via Panasonic's sender ID | Cloud | – | Skip — needs Panasonic's FCM project |
| Predictive-fault feedback, service requests, e-warranty | `analytics/pm/…`, eWarranty host | Cloud | – | Skip — support workflows |
| Alexa / Google / Intesis / Comfort Cloud linking, weather card, device images | various | Cloud | – | Skip |
| Remove device / onboarding / Wi-Fi change | offboard + BLE provisioning | Cloud + BLE | – | Skip — use the MirAIe app |

## WiZ (bulbs)

Caveat: WiZ homes can require **signed** local commands ("Only verified controls", HMAC with a cloud-issued key). Our unsigned commands work only with "Allow all controls".

| Feature | Mechanism | Local / Cloud | Status |
|---|---|---|---|
| Discovery, on/off, brightness, white temp, RGB | `getPilot` broadcast, `setPilot` | Local | Have |
| Scenes 1–32 with speed, grouped | `setPilot {sceneId, speed}` | Local | Have |
| Identify, rename, all on/off | `setPilot`; names stored locally | Local | Have |
| **Identify via `pulse`** | `{"method":"pulse","params":{"delta":-N,"duration":ms}}` | Local | **Build** (fallback to blink) |
| **Extra scenes** 33 (Diwali), 35 (Alarm) | `sceneId` (ids from bundled JSON) | Local | **Build** |
| **Local rooms / groups** with group on/off, brightness, scene | client-side fan-out | Local | **Build** |
| **Saved light presets** (my colours / room scenes) | client-side, replayed via `setPilot` | Local | **Build** |
| **Push state** (no polling) | `registration` → `syncPilot` pushes | Local | Later (listen port unconfirmed) |
| WiZ ceiling fan | `setPilot` `fanState/fanMode/fanSpeed/fanRevrs` | Local | Later (only if a fan is found; value ranges unconfirmed) |
| Dual-zone ratio | `setPilot` `ratio` (key unconfirmed) | Local | Later |
| Fade in/out, min dimming, startup mode | `setSystemConfig` (inferred) | Local? | Later (unconfirmed) |
| Reboot | `reboot` | Local | Later |
| Music sync | `syncAmbience` / `setEffect` (params unknown) | Local | Later (needs packet capture) |
| Rhythms, schedules, away/vacation, SpaceSense, energy history, OTA, accessories, sync box | WiZ cloud | Cloud | Skip — needs a WiZ account; local schedules may come later |
