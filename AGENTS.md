# AGENTS.md

Guide for coding agents (and humans) working in this repo. Read [README.md](README.md) for what the project does, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how, and [PROTOCOL.md](PROTOCOL.md) for the wire formats.

## Layout
- `mac/` — SwiftPM package `HomeBar` (macOS 14+ menu bar app). No third-party dependencies.
- `android/` — Gradle project, single `app` module, package `in.canvs.homecontrol`.
- `PROTOCOL.md` — source of truth for protocols. Code comments reference its sections (`§1.6` etc.); keep section numbers stable.
- `docs/` — architecture, reverse-engineering notes, roadmap, screenshots.

## Build and verify

| Task | Command |
|---|---|
| Mac release app | `cd mac && ./build-app.sh` → `mac/HomeBar.app` |
| Mac debug build | `cd mac && swift build` |
| Mac UI snapshots (sample data) | `cd mac && HOMEBAR_SNAPSHOT=/some/dir .build/debug/HomeBar` |
| Mac self-checks (key code round trip) | `cd mac && HOMEBAR_KEYTEST=1 .build/debug/HomeBar` |
| Android debug APK | `cd android && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug` |
| Android unit tests | `cd android && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest` |
| Android demo UI | install the debug APK on an **emulator**, then `adb shell am start -n in.canvs.homecontrol/.MainActivity --ez demo true` |

Every change must build. UI changes should be checked visually (snapshots or emulator screenshots) in light and dark mode.

## Rules
1. **Feature parity:** a user-facing feature added to one app goes into the other with the same behaviour and wording, unless the platform makes it impossible. Note any intentional difference in `docs/ARCHITECTURE.md`.
2. **Key-code format is shared:** `miraie-local-v1:` + base64(JSON array). The Mac writes keys sorted. New fields must be optional so older codes still import on both apps.
3. **Secrets:** never log or copy the password, access/refresh tokens, or the full LAN key/IV. Diagnostics must stay redacted; the tests and self-checks above enforce this, so keep them passing.
4. **Never commit** vendor APKs, `extracted/` (decompiled vendor code), build outputs, `local.properties`, or real diagnostics from a user's device.
5. **Don't test on real devices without the owner:** no installs to physical phones, and no commands to real ACs or bulbs, unless the user asks.
6. **Protocol changes:** update `PROTOCOL.md` first, then both apps. Mark anything not confirmed on a real device as *unverified*.

## Gotchas
- **No Xcode on the dev machine:** with Command Line Tools only, SwiftUI's `@State` macro plugin is missing. Use `@ViewState` (a typealias in `StateCompat.swift`) instead of `@State`. `@Environment`, `@AppStorage`, `@StateObject` and `@EnvironmentObject` work normally.
- The Mac app's release build excludes everything under `#if DEBUG` (snapshot harness, demo seeding).
- Android needs **JDK 17**; newer JDKs break the AGP version in use.
- Material 3 `Slider(track = …)` is experimental; opt in with `@OptIn(ExperimentalMaterial3Api::class)`.
- MirAIe UDP: discovery request is plaintext; everything else is AES-CBC with zero padding (not PKCS#7). Trailing `0x00` must be stripped after decrypt.
- MirAIe command values are strings for temperature (`"24"`, `"24.5"`), matching the official app.
- WiZ replies come from the bulb's IP; discovery must collect for the full timeout because bulbs answer at different times.
