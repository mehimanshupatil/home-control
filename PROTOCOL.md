# Device protocols (reverse-engineered)

Sources: `Panasonic-MirAIe_01.4.32` (native Android, decompiled with jadx) and
`WiZ-Connected_1.38.6` (Flutter AOT, `libapp.so` string analysis + public protocol).

---

## 1. Panasonic MirAIe (cloud: HTTPS + MQTT)

Constants (`com.panasonic.in.miraie.BuildConfig`):

| Name | Value |
|---|---|
| AUTH base | `https://auth.miraie.in/simplifi/v1/` |
| API base | `https://app.miraie.in/simplifi/v1/` |
| MQTT broker | `ssl://mqtt.miraie.in:8883` (system CA trust, no pinning) |
| IAM client id | `292d99d7-5623-4157-8a71-846ee3ff5dbf` |

### 1.1 Login — `POST {AUTH}userManagement/login`

```json
{ "clientId": "292d99d7-5623-4157-8a71-846ee3ff5dbf",
  "mobile": "+91XXXXXXXXXX",        // or "email": "..."
  "password": "...",
  "scope": "an_<nanoTime % 1e9>" }
```
Response: `{ "accessToken", "refreshToken", "expiresIn", "userId" }`.

Refresh — `POST {AUTH}userManagement/tokenRefresh` body `{ "clientId", "token": <refreshToken> }` → same response.

All API calls: header `Authorization: Bearer <accessToken>`.

### 1.2 Homes / devices — `GET {API}homeManagement/homes`

Returns array of homes:
```json
[{ "homeId": "...", "homeName": "...",
   "spaces": [{ "spaceId": "...", "spaceName": "...",
     "devices": [{ "deviceId": "...", "deviceName": "...", "modelId": "...",
                   "category": "AC", "topic": ["<homeId>/<deviceId>"] }] }] }]
```

### 1.3 Device model (capability keys) — `GET {API}deviceManagement/deviceModels/modelId/{modelId}`

`controlCapabilities[]`: `{ "capability": "<Name>", "attributes": [{ "key": "<jsonKey>", ... }] }`.
The JSON key used on MQTT is `attributes[0].key`. `ki` (key index) is a fixed per-capability
constant from the app's `BaseCapability.Type` enum:

| capability | ki | typical key | values |
|---|---|---|---|
| PowerStatus | 1 | `ps` | `"on"` / `"off"` |
| ACMode | 0 | `acmd` | `auto` `cool` `heat` `dry` `fan` |
| ACTemperature | 2 | `actmp` | number (e.g. `24` / `24.5`) |
| PowerfulMode | 3 | `acpm` | on/off |
| ACFanSpeed | 4 | `acfs` | `auto` `high` `medium` `low` `quiet` |
| VerticalSwing | 5 | `acvs` | int (0 = auto) |
| HorizontalSwing | 6 | `achs` | int |
| NanoeG | 7 | `acng` | on/off |
| ECOMode | 10 | `acem` | on/off |
| RoomTemperature (status) | 101 | `rmtmp` | number |

Apps should read keys from the model response and fall back to the "typical key" column.

### 1.4 Initial status — `GET {API}deviceManagement/devices/{deviceId}/mobile/status`

Flat JSON with the same keys (`ps`, `acmd`, `actmp`, `rmtmp`, `acfs`, …, `onlineStatus`).

### 1.5 MQTT

- Client id: `"AN" + sha256(userId + "##" + homeId)[0:16] + <5 digits>`
- Username: `homeId`
- Password: `accessToken`
- Clean session, keep-alive ~30 s, MQTT 3.1.1.
- Subscribe: `<topic>/status` for live state (`<topic>/pstatus` = power stats).
- Publish control (QoS 0, not retained) to `<topic>/control`
  (`/tcontrol` for IntesisAC / Comfort-Cloud bridged ACs):

```json
{ "ps": "on", "ki": 1, "cnt": "an", "sid": "1" }
{ "actmp": "24.0", "ki": 2, "cnt": "an", "sid": "1" }
{ "acmd": "cool", "ki": 0, "cnt": "an", "sid": "1" }
```

### 1.6 Local LAN control (no cloud at runtime) — `UDPService`

Supported when the device model lists the `udpControl` config capability.

- One-time key fetch (needs cloud once): `GET {API}deviceManagement/devices/deviceId/{deviceId}`
  → `[{ "macAddress", "ipAddress", "udpControlKey": "<hex>", "udpcontrolIV": "<hex>", ... }]`.
  Store key/IV/MAC; afterwards no Panasonic server is needed.
- Transport: UDP port **5001** both ways (app binds local 5001 to receive).
- Crypto: AES-CBC/NoPadding, key & IV from hex; plaintext zero-padded to 16 bytes;
  strip trailing `0x00` after decrypt.
- Discover: broadcast **plaintext** `DISC_REQ_<MAC uppercase, no colons>` to subnet broadcast:5001.
  AC replies encrypted `DISC_RSP_<mac no colons>` (case-insensitive) → sender IP = AC IP.
- Control: encrypt `{"<key>": <value>, "ki": 1, "cnt": "an", "sid": "1"}` → send to AC_IP:5001
  (note: `ki` is always 1 on the UDP path).
- Status: AC sends encrypted status JSON (same keys as `/status`) back to port 5001.
- Device JSON also exposes `isMatterEnabled`; Matter-enabled units can be controlled
  locally via Matter (e.g. Apple Home) instead.

### 1.7 Full AC capability catalogue (`BaseCapability.Type`)

Each model's `controlCapabilities[]` lists the subset it supports; the MQTT/UDP key is
`attributes[0].key`. `ki` below is the cloud key index (UDP always sends `ki: 1`).

| capability | ki | payload value |
|---|---|---|
| ACMode | 0 | `auto` `cool` `heat` `dry` `fan` |
| PowerStatus | 1 | `on`/`off` |
| ACTemperature | 2 | number |
| PowerfulMode | 3 | `on`/`off` |
| ACFanSpeed | 4 | `auto` `quiet` `low` `medium` `high` |
| VerticalSwing | 5 | int within attribute range (0 likely auto — unverified) |
| HorizontalSwing | 6 | int within attribute range |
| NanoeG | 7 | `on`/`off` |
| DisplayControl | 8 | `on` `night` `off` |
| EvaporatorClean | 9 | `on`/`off` |
| ECOMode | 10 | `on`/`off` |
| SleepProfile | 12 | `on`/`off` + `"pid": <profileId>` (profiles are cloud data) |
| JetStream | 13 | `on`/`off` |
| ACTimer | 14 | array `[offTimer, onTimer]`; either relative seconds or UTC epoch seconds (two builders exist; `-1` = unset) |
| FilterDustLevel | 15 | status only |
| Ionizer | 16 | `on`/`off` |
| MotionSensor | 17 | `on`/`off` |
| TurboMode | 18 | `on`/`off` |
| ACGenMode | 19 | int: 40%→5, 60%→4, 80%→3, 100%→2, 110%→1, off→0 |
| PM25Sensor | 20 | status only |
| EcoNavi | 21 | `on`/`off` |
| NanoeX | 23 | `on`/`off` |
| TimeBasedFilterHealth | 25 | status only |
| EconomySensor | 27 | `on`/`off` |
| Convertible | 28 | int: 40 50 55 60 70 80 90 100 110, off→0 |
| BuzzerControl | 30 | `on`/`off` |
| RoomTemperature | 101 | status |
| RoomHumidity | 102 | status |
| CurrentPowerConsumption | 104 | status |
| ACFaults | 107 | status |
| udpControl | 110 | config capability (LAN control supported) |

Power-consumption history: `GET {API}powerConsumption/homes/{homeId}` (cloud only).

### 1.8 Confirmed on a real AC (model `130185`, firmware `3.02`)

Observed from diagnostics on 2026-10-13:
- `configCapabilities` = `[ProximityNotification]` only: **no `udpControl`**, so the official app never uses LAN control for this model. It's cloud (MQTT) only unless the LAN path is proven to work anyway.
- The model attributes drive the UI: modes `auto cool dry fan` (no heat), temperature 16–30 in **1°** steps, display `on/off` only, Convertible `intValues` `[110,100,90,80,70,55,40,0]`, swing ranges 0–5.
- **Timer (`actm`, ki 14):** the official app (`ACDetailsActivity` → `ACDevice.setOff/OnTimerInSeconds` → `TimerValueCapability.buildOff/OnTimerMessage`) sends **relative seconds** `[offSeconds, onSeconds]`, `-1` = unset, and keeps the other timer as its remaining seconds. The picker range is 10–1440 **minutes** in steps of 10. Status reports `actm` as **UTC epoch seconds** `[offUTC, onUTC]`, `-1` = unset.
- Sleep profile `acsp`: status `"none"`; attributes `duration` 2–8 h and `temps` 16–30.
- Real status keys: `ps acmd actmp acfs acvs achs acdc acem acec acpm bzr cnv actm acsp rmtmp`, plus `errors`, `warnings` (strings, empty = none), `filterDustLevel`, `filterCleaningRequired`, `filterCleaningCause`, `totalOperatingHours`, `rssi`, `onlineStatus` ("true"), `V` (firmware). Also `acng acngs acms acmss acfc acgm acpms acdl ldy`, which aren't in this model's control list.
- Temperature in status is a string with one decimal (`"26.0"`). The official app also **sends** it that way (`DecimalFormat("#.0")`).
- When ECO is switched on, the official app also sets the temperature to 26° (`ACDevice`, `DEFAULT_AC_TEMPERATURE_FOR_ECOMODE`). Our apps don't do this yet.

### 1.9 Energy consumption history (cloud only)

`GET {API}powerConsumption/devices/{deviceId}?grain=<Daily|Weekly|Monthly>&startDate=<d>&endDate=<d>`
(`IDeviceService.getPowerConsumption`, UI in `miraie/widgets/EnergyConsumption.java` and `devicedetails/ac/powerconsumption/DayHelper.java`).

| grain | date format | window the official app requests |
|---|---|---|
| `Daily` | `ddMMyyyy` | 7 days (start + 6) |
| `Weekly` | `ddMMyyyy` | 7 weeks (start of week 1 … start of week 7) |
| `Monthly` | `MMyyyy` | a range of months |

Response: `[{ "day" | "week" | "month": "<label>", "power": "<kWh as string>" }]`. The unit shown in the app is **kWh**.
Days with no data may be missing; the app fills gaps with zero. The home-level `powerConsumption/homes/{homeId}` endpoint belongs to the third-party "Ohm" integration, not the AC.

---

## 2. WiZ (local LAN: UDP JSON-RPC)

Port **38899/UDP**, JSON per datagram. Confirmed method names in `libapp.so`:
`setPilot`, `syncPilot`, `registration`, `setState`, broadcast `255.255.255.255`.

- Discover: broadcast `{"method":"getPilot","params":{}}` → each bulb replies
  `{"method":"getPilot","env":"pro","result":{"mac":"..","rssi":-60,"state":true,"sceneId":0,"temp":4200,"dimming":80}}`
  (colour mode returns `r`,`g`,`b` instead of `temp`).
- Control: `{"id":1,"method":"setPilot","params":{...}}` → `{"result":{"success":true}}`

| param | range |
|---|---|
| `state` | bool |
| `dimming` | 10–100 |
| `temp` | 2200–6500 K |
| `r`,`g`,`b` | 0–255 |
| `sceneId` | 1–32 (+ `speed` 10–200) |

Bulb names live in the WiZ cloud; these apps let you rename locally.
Fully local: works with bulbs blocked from the internet, provided "Allow local communication"
is enabled in the WiZ app settings (one-time).
