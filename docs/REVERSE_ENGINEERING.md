# Reverse-engineering notes

How [PROTOCOL.md](../PROTOCOL.md) was derived, so it can be re-checked when the vendor apps update. Vendor APKs and decompiled output are **not** in this repo (see `.gitignore`); obtain the apps yourself.

## Inputs
| App | Version analysed | Tech | Approach |
|---|---|---|---|
| Panasonic MirAIe (`com.panasonic.in.miraie`) | 01.4.32 | Native Android (Kotlin/Java, 6 dex) | Full decompile with jadx |
| WiZ Connected | 1.38.6 | Flutter AOT (`libapp.so`) + React Native parts | String analysis of `libapp.so`; protocol matches the public WiZ LAN API |

Both were `.apks` bundles (zip of split APKs); `base.apk` holds the code.

## Reproduce
```bash
mkdir -p extracted/miraie extracted/wiz
unzip -o Panasonic-MirAIe_*.apks -d extracted/miraie
unzip -o WiZ-Connected_*.apks -d extracted/wiz
brew install jadx
jadx -j 8 --no-res --no-debug-info -d extracted/miraie_src extracted/miraie/base.apk
unzip -o extracted/wiz/split_config.arm64_v8a.apk lib/arm64-v8a/libapp.so -d extracted/wizlib
```
The REA MCP tools were used for triage. Their Android (JADX) provider needs `REA_JADX_MCP_JAR` configured; without it, the jadx CLI above does the job.

## Where things live in the MirAIe decompile
Paths under `extracted/miraie_src/sources/com/panasonic/in/`:

| Topic | File |
|---|---|
| Endpoints, client IDs, MQTT broker | `miraie/BuildConfig.java`, `miraie/di/EndpointModule.java` |
| Login / refresh bodies | `miraiecore/apis/authentication/{IAuthenticationService,AuthenticationService,LoginRequestBody}.java` |
| Homes / devices JSON | `miraiecore/apis/home/UserHome.java` |
| Device details (LAN key/IV) | `miraiecore/apis/device/{IDeviceService,DeviceDetails}.java` |
| MQTT connect, client id, topics | `miraiecore/connections/mqtt/MQTTService.java`, `entity/device/BaseDevice.java` |
| LAN control (UDP 5001, AES) | `miraiecore/connections/udp/UDPService.java` |
| Capability catalogue and `ki` | `miraiecore/entity/device/capabilities/BaseCapability.java` (`Type` enum) |
| Capability payloads | `.../capabilities/*Capability.java`, `.../capabilities/ac/*`, `.../capabilities/attributes/*` |
| Model → capability parsing | `miraiecore/entity/device/DeviceModelDeserializer.java` |

## Key findings
- The MQTT username is the `homeId` and the password is the user access token. The client id is `"AN" + sha256(userId##homeId)[0:16] + 5 digits`.
- `ki` is a fixed per-capability index from the `Type` enum, not always 1. The LAN path overwrites it with 1.
- The LAN key/IV come from the cloud device record. The app never generates or rotates them; how long they stay valid is decided server- or firmware-side (unverified).
- The capability JSON key is `attributes[0].key`, falling back to the capability name.
- WiZ method names confirmed in the binary: `setPilot`, `syncPilot`, `registration`, `setState`, `pulse`, `reboot`; fields `fanState`, `fanSpeed`, `fanMode`, `fanRevrs`, `speed`, `fadeIn`, `fadeOut`, `startupMode`. `getPilot` isn't present as a literal; it's the standard public method and works for discovery.

## Re-verifying after a vendor update
1. Decompile the new version as above.
2. Diff the files in the table, especially `BuildConfig`, `UDPService`, `MQTTService` and `BaseCapability`.
3. Update `PROTOCOL.md` first, then both apps (see AGENTS.md rule 6).
