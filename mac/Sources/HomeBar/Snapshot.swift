#if DEBUG
import AppKit
import SwiftUI

/// Debug-only: `HOMEBAR_SNAPSHOT=/dir swift run` renders the panel with sample data to PNGs and exits.
@MainActor
enum Snapshot {
    static func keyRoundTrip() {
        let entry = ACStore.ExportedAC(id: "t1", name: "Test AC", space: "Room", homeId: "h", topic: "h/t1", modelId: "m",
                                       keys: ACKeys(), mac: "AA:BB:CC:DD:EE:FF", ip: "192.168.1.50",
                                       key: "00112233445566778899aabbccddeeff", iv: "0f0e0d0c0b0a09080706050403020100")
        let enc = JSONEncoder(); enc.outputFormatting = .sortedKeys
        let code = ACStore.keyCodePrefix + (try! enc.encode([entry])).base64EncodedString()
        let ac = ACStore()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        ac.importKeyCode(code)
        let again = ac.exportKeyCode()
        print("import ok:", ac.loggedIn, ac.localOnly, ac.devices.map(\.name), "error:", ac.errorMessage ?? "none")
        print("round trip equal:", again == code)
        ac.importKeyCode("garbage")
        print("bad code error:", ac.errorMessage ?? "none")
        Keychain.delete(account: "local-t1")
        UserDefaults.standard.removeObject(forKey: "acDevices")
        UserDefaults.standard.set(false, forKey: "acLocalOnly")
        exit(0)
    }

    static func runIfRequested() {
        if ProcessInfo.processInfo.environment["HOMEBAR_KEYTEST"] != nil { keyRoundTrip() }
        guard let dir = ProcessInfo.processInfo.environment["HOMEBAR_SNAPSHOT"] else { return }
        _ = NSApplication.shared
        for (tab, tabName) in [(0, "climate"), (1, "lights")] {
            for (appearance, mode) in [(NSAppearance.Name.aqua, "light"), (.darkAqua, "dark")] {
                UserDefaults.standard.set(tab, forKey: "selectedTab")
                let ac = ACStore()
                let lights = LightsStore()
                RunLoop.main.run(until: Date().addingTimeInterval(0.3)) // let startup tasks settle
                ac.seedDemo(); lights.seedDemo()
                ac.devices[0].state.raw["acsp"] = .string("p1") // sleep running on the first AC
                let root = ContentView()
                    .environmentObject(ac)
                    .environmentObject(lights)
                    .environmentObject(Navigator())
                    .background(.windowBackground)
                let host = NSHostingView(rootView: root)
                host.appearance = NSAppearance(named: appearance)
                host.frame = NSRect(origin: .zero, size: host.fittingSize)
                let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
                window.appearance = NSAppearance(named: appearance)
                window.contentView = host
                host.layoutSubtreeIfNeeded()
                guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { continue }
                host.cacheDisplay(in: host.bounds, to: rep)
                let url = URL(fileURLWithPath: dir).appendingPathComponent("\(tabName)-\(mode).png")
                try? rep.representation(using: .png, properties: [:])?.write(to: url)
                print("wrote", url.path)
            }
        }
        renderKeysCard(dir: dir)
        renderExtras(dir: dir)
        checkDiagnosticsRedaction()
        exit(0)
    }

    /// Renders any view at panel width to `<dir>/<name>-<light|dark>.png`.
    private static func render<V: View>(_ view: V, name: String, dir: String, ac: ACStore, lights: LightsStore) {
        for (appearance, mode) in [(NSAppearance.Name.aqua, "light"), (.darkAqua, "dark")] {
            let root = view.environmentObject(ac).environmentObject(lights).environmentObject(Navigator())
                .padding(16).frame(width: 380).background(.windowBackground)
            let host = NSHostingView(rootView: root)
            host.appearance = NSAppearance(named: appearance)
            host.frame = NSRect(origin: .zero, size: host.fittingSize)
            let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
            window.appearance = NSAppearance(named: appearance)
            window.contentView = host
            host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.4)) // let `.task` loaders finish
            host.frame = NSRect(origin: .zero, size: host.fittingSize)
            window.setContentSize(host.frame.size)
            host.layoutSubtreeIfNeeded()
            guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { continue }
            host.cacheDisplay(in: host.bounds, to: rep)
            let url = URL(fileURLWithPath: dir).appendingPathComponent("\(name)-\(mode).png")
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
            print("wrote", url.path)
        }
    }

    /// Renders the whole panel (header, tabs or a pushed page) at full content height.
    private static func renderPanel(_ route: Route?, tab: HomeTab, name: String, dir: String,
                                    ac: ACStore, lights: LightsStore, unbounded: Bool = true) {
        UserDefaults.standard.set(tab.rawValue, forKey: "selectedTab")
        ContentView.debugUnbounded = unbounded
        defer { ContentView.debugUnbounded = false }
        for (appearance, mode) in [(NSAppearance.Name.aqua, "light"), (.darkAqua, "dark")] {
            let root = ContentView()
                .environmentObject(ac).environmentObject(lights).environmentObject(Navigator(route: route))
                .background(.windowBackground)
            let host = NSHostingView(rootView: root)
            host.appearance = NSAppearance(named: appearance)
            host.frame = NSRect(origin: .zero, size: host.fittingSize)
            let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
            window.appearance = NSAppearance(named: appearance)
            window.contentView = host
            host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.4)) // let `.task` loaders finish
            host.frame = NSRect(origin: .zero, size: host.fittingSize)
            window.setContentSize(host.frame.size)
            host.layoutSubtreeIfNeeded()
            guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { continue }
            host.cacheDisplay(in: host.bounds, to: rep)
            let url = URL(fileURLWithPath: dir).appendingPathComponent("\(name)-\(mode).png")
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
            print("wrote", url.path)
        }
    }

    private static func renderExtras(dir: String) {
        let ac = ACStore()
        let lights = LightsStore()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        ac.seedDemo(); lights.seedDemo()
        Keychain.save(LocalACInfo(mac: "AA:BB:CC:DD:EE:FF", ip: "192.168.1.50", key: demoKey, iv: demoIV), account: "local-d1")
        defer { Keychain.delete(account: "local-d1") }
        ac.devices[0].state.raw["acsp"] = .string("p1") // sleep profile running on the first AC

        renderPanel(nil, tab: .climate, name: "climate-full", dir: dir, ac: ac, lights: lights)
        render(ACCard(device: ac.devices[1]), name: "ac-off", dir: dir, ac: ac, lights: lights)
        for page in ACPage.allCases {
            renderPanel(.ac("d1", page), tab: .climate, name: "page-\(page.rawValue)", dir: dir, ac: ac, lights: lights)
        }
        renderPanel(.ac("d3", .air), tab: .climate, name: "page-air-rich", dir: dir, ac: ac, lights: lights)
        renderPanel(.ac("d3", .more), tab: .climate, name: "page-more-rich", dir: dir, ac: ac, lights: lights)
        renderPanel(.ac("d2", .air), tab: .climate, name: "page-air-unknown", dir: dir, ac: ac, lights: lights)
        if let night = ac.sleepProfiles["d1"]?.last {
            ACSleepPanel.debugEditing = night
            renderPanel(.ac("d1", .sleep), tab: .climate, name: "page-sleep-editor", dir: dir, ac: ac, lights: lights)
            ACSleepPanel.debugEditing = nil
        }
        if let clean = ac.devices[0].capability("EvaporatorClean") {
            ac.send(ac.devices[0], capability: clean, value: "on")
            renderPanel(.ac("d1", .air), tab: .climate, name: "page-air-clean", dir: dir, ac: ac, lights: lights)
            renderPanel(nil, tab: .climate, name: "climate-cleaning", dir: dir, ac: ac, lights: lights)
            ac.send(ac.devices[0], capability: clean, value: "off")
        }
        renderPanel(.notifications, tab: .climate, name: "page-notifications", dir: dir, ac: ac, lights: lights)
        renderPanel(.diagnostics, tab: .climate, name: "page-diagnostics", dir: dir, ac: ac, lights: lights)
        renderPanel(.keys, tab: .climate, name: "page-keys", dir: dir, ac: ac, lights: lights)
        renderPanel(nil, tab: .lights, name: "lights-full", dir: dir, ac: ac, lights: lights)
        renderPanel(.rooms, tab: .lights, name: "page-rooms", dir: dir, ac: ac, lights: lights)
        renderPanel(.bulb(lights.bulbs[0].id), tab: .lights, name: "page-bulb", dir: dir, ac: ac, lights: lights)
        if let moving = lights.bulbs.first(where: { LightsStore.movingScenes.contains($0.sceneId) }) {
            renderPanel(.bulb(moving.id), tab: .lights, name: "page-bulb-scene", dir: dir, ac: ac, lights: lights)
        }
        // README screenshots: real panel size.
        renderPanel(nil, tab: .climate, name: "readme-climate", dir: dir, ac: ac, lights: lights, unbounded: false)
        renderPanel(nil, tab: .lights, name: "readme-lights", dir: dir, ac: ac, lights: lights, unbounded: false)
        renderPanel(.ac("d1", .air), tab: .climate, name: "readme-air", dir: dir, ac: ac, lights: lights, unbounded: false)
    }

    static let demoKey = "00112233445566778899aabbccddeeff"
    static let demoIV = "0f0e0d0c0b0a09080706050403020100"

    /// Diagnostics must never contain the full LAN key/IV, tokens or the password.
    private static func checkDiagnosticsRedaction() {
        let ac = ACStore()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        ac.seedDemo()
        Keychain.save(LocalACInfo(mac: "AA:BB:CC:DD:EE:FF", ip: "192.168.1.50", key: demoKey, iv: demoIV), account: "local-d1")
        defer { Keychain.delete(account: "local-d1") }
        let json = ac.diagnosticsJSON()
        let forbidden = [demoKey, demoIV, "accessToken", "refreshToken", "password", "Bearer"]
        let leaks = forbidden.filter { json.contains($0) }
        print("diagnostics redaction:", leaks.isEmpty ? "PASS" : "FAIL \(leaks)")
        print("diagnostics masked key present:", json.contains("\"0011…\""))
        print("diagnostics has model + status:", json.contains("controlCapabilities") && json.contains("\"acdc\""))
        print("diagnostics has local fields:", json.contains("lanKeyAvailable") && json.contains("lastLocalDiscovery"))
        checkModelParsing(ac)
        checkCloudPayloads()
        checkSideEffects()
    }

    /// Model 130185 must drive the core controls (no Heat, 1° steps, Convertible from intValues).
    private static func checkModelParsing(_ ac: ACStore) {
        let d = ac.devices[0]
        let conv = d.capability("Convertible").map { cap -> [Int] in
            if case .intChoice(let o) = cap.kind { return o.map(\.value) }
            return []
        } ?? []
        let display = d.capability("DisplayControl").map { cap -> [String] in
            if case .choice(let v) = cap.kind { return v }
            return []
        } ?? []
        let checks: [(String, Bool)] = [
            ("modes", d.modeOptions == ["auto", "cool", "dry", "fan"]),
            ("fan", d.fanOptions == ["auto", "quiet", "low", "medium", "high"]),
            ("temp range", d.tempRange == ACRange(min: 16, max: 30, step: 1)),
            ("timer range", d.timerRange == ACRange(min: 10, max: 1440, step: 10)),
            ("convertible", conv == [40, 55, 70, 80, 90, 100, 110, 0]),
            ("display", display == ["on", "off"]),
            ("no model defaults", ac.devices[1].modeOptions.contains("heat") && ac.devices[1].tempRange.step == 0.5),
        ]
        for (name, ok) in checks { print("model parsing \(name):", ok ? "PASS" : "FAIL") }

        // Timer payload: relative seconds, other slot kept as remaining seconds.
        var sent: [Int] = []
        if let cap = d.capability("ACTimer") {
            ac.setTimer(d, .on, seconds: 3600)
            sent = ac.devices[0].state.raw[cap.key]?.arrayValue.compactMap(\.intValue) ?? []
        }
        let now = Int(Date().timeIntervalSince1970)
        let offOK = sent.count == 2 && abs(sent[0] - (now + 7200)) < 5
        let onOK = sent.count == 2 && abs(sent[1] - (now + 3600)) < 5
        print("timer optimistic status (UTC) keeps off + sets on:", offOK && onOK ? "PASS" : "FAIL \(sent)")
    }

    private static func report(_ name: String, _ ok: Bool, _ detail: Any = "") {
        print("\(name):", ok ? "PASS" : "FAIL \(detail)")
    }

    /// Sleep-profile, rule and energy payloads must match the official app's formats.
    private static func checkCloudPayloads() {
        // Sleep profile formatting (SleepProfileEntity) and activation (ACProfileCapability.buildMessage).
        let p = SleepProfile(id: "p9", name: "Cool night", temps: [26, 27, 28.5], offAtEnd: true)
        report("sleep temperatures %04.1f + off", p.temperatureStrings == ["26.0", "27.0", "28.5", "00.0"], p.temperatureStrings)
        let f = p.activationFields(key: "acsp", on: true)
        report("sleep activation fields", f["acsp"] as? String == "on" && f["pid"] as? String == "p9"
               && (f["temps"] as? [String]) == p.temperatureStrings, f)
        let def = SleepProfile(id: "x", name: "default", temps: [26], offAtEnd: false)
        report("default profile pid", def.activationFields(key: "acsp", on: false)["pid"] as? String == "default")
        let parsed = SleepProfile(entity: ["profileId": "p9", "profileName": "N", "temperatures": ["26.0", "27.0", "00.0"], "smartRuleId": ["r1"]])
        report("sleep entity parse (trailing 00.0 = off at end)", parsed?.temps == [26, 27] && parsed?.offAtEnd == true && parsed?.ruleIds == ["r1"])
        report("rule payload string", p.rulePayloadString(key: "acsp", ki: 12) == #"{"acsp":"on","ki":12,"pid":"p9"}"#,
               p.rulePayloadString(key: "acsp", ki: 12))

        // Rule (RuleItem.getRulePayload) shape.
        let body = SleepSchedule.rulePayload(name: "Schedule Cool night", homeId: "h", userId: "u", deviceId: "d1", profile: p,
                                             payload: p.rulePayloadString(key: "acsp", ki: 12), hour: 22, minute: 30, weekdays: [1, 2])
        let trig = ((body["triggers"] as? [[[String: Any]]])?.first?.first) ?? [:]
        let action = (((body["rule"] as? [String: Any])?["actions"]) as? [[String: Any]])?.first ?? [:]
        report("rule trigger", trig["type"] as? String == "Timer" && trig["check"] as? String == "EQ"
               && trig["property"] as? String == "Time" && trig["value"] as? Int == 2230 && trig["repeatType"] as? String == "S,M", trig)
        report("rule action", action["deviceId"] as? String == "d1" && action["profileId"] as? String == "p9"
               && action["payloadVersion"] as? String == "0.1" && (action["payload"] as? String)?.contains("\"pid\":\"p9\"") == true, action)
        report("rule header", body["schemaVersion"] as? String == "v2" && body["ruleType"] as? String == "User"
               && body["ruleStatus"] as? String == "ENABLE" && body["primaryUserId"] as? String == "u" && body["ruleId"] == nil)
        let all = SleepSchedule(ruleId: "", hour: 0, minute: 0, weekdays: Set(1...7), enabled: true)
        let once = SleepSchedule(ruleId: "", hour: 0, minute: 0, weekdays: [], enabled: true)
        report("rule repeatType daily/once/parse", all.repeatType == "daily" && once.repeatType == "once"
               && SleepSchedule.weekdays(from: "S,TH,SA") == [1, 5, 7])
        let back = SleepSchedule(ruleId: "r", rule: ["ruleStatus": "PAUSE", "triggers": [[["value": 615, "repeatType": "M,W"]]]])
        report("rule parse", back?.hour == 6 && back?.minute == 15 && back?.weekdays == [2, 4] && back?.enabled == false)

        // Energy windows (DayHelper): ddMMyyyy / MMyyyy, zero-filled buckets.
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Kolkata")!
        let now = cal.date(from: DateComponents(year: 2026, month: 10, day: 13, hour: 15))!
        let d = EnergyWindow.make(.daily, now: now, calendar: cal)
        let w = EnergyWindow.make(.weekly, now: now, calendar: cal)
        let m = EnergyWindow.make(.monthly, now: now, calendar: cal)
        report("energy daily window", d.startDate == "07102026" && d.endDate == "13102026" && d.buckets.count == 7, (d.startDate, d.endDate))
        report("energy weekly window", w.startDate == "26082026" && w.endDate == "07102026" && w.buckets.count == 7, (w.startDate, w.endDate))
        report("energy monthly window", m.startDate == "042026" && m.endDate == "102026" && m.buckets.count == 7, (m.startDate, m.endDate))
        let pts = d.points(from: [["day": "09102026", "power": "4.25"], ["day": "13102026", "power": 1.5]])
        report("energy zero-fill", pts.map(\.kWh) == [0, 0, 4.25, 0, 0, 0, 1.5], pts.map(\.kWh))
    }

    /// Official side effects (ACDevice.setPowerStatus / setEcoMode / setACPowerfulMode / setACJetStreamMode).
    private static func checkSideEffects() {
        let ac = ACStore()
        ac.seedDemo()
        func keys() -> [String] {
            ac.sentLog.map { f in f.keys.filter { !["pid", "temps"].contains($0) }.sorted().map { "\($0)=\(f[$0]!)" }.joined(separator: ",") }
        }
        // Power on from 20° in cool → also 24°.
        if let i = ac.devices.firstIndex(where: { $0.id == "d1" }) {
            ac.devices[i].state.power = false
            ac.devices[i].state.setTemp = 20
            ac.sentLog = []
            ac.setPower(ac.devices[i], true)
            report("power on raises to 24°", keys().prefix(2) == ["ps=on", "actmp=24.0"], keys())
        }
        // ECO on → 26°.
        let d1 = ac.devices[0]
        if let eco = d1.capability("ECOMode") {
            ac.sentLog = []
            ac.setToggle(d1, eco, true)
            report("ECO on sets 26°", keys().prefix(2) == ["acem=on", "actmp=26.0"], keys())
        }
        // Powerful on → Convertible off first.
        if let pm = d1.capability("PowerfulMode") {
            ac.sentLog = []
            ac.setToggle(ac.devices[0], pm, true)
            report("Powerful turns Convertible off first", keys() == ["cnv=0", "acpm=on"], keys())
        }
        // JetStream on (rich model) → Convertible off, jet on, swings 1, running custom profile stopped.
        let d3 = ac.devices[2]
        if let jet = d3.capability("JetStream") {
            ac.sentLog = []
            ac.setToggle(d3, jet, true)
            report("JetStream side effects", keys() == ["cnv=0", "acjs=on", "acvs=1", "achs=1", "acsp=off"], keys())
        }
        // Mode change stops a running custom profile.
        if let i = ac.devices.firstIndex(where: { $0.id == "d3" }) {
            ac.devices[i].state.raw["acsp"] = .string("p1")
            ac.sentLog = []
            ac.setMode(ac.devices[i], "dry")
            report("mode change stops sleep profile", keys() == ["acmd=dry", "acsp=off"], keys())
        }
    }

    private static func renderKeysCard(dir: String) {
        let ac = ACStore()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        ac.seedDemo()
        Keychain.save(LocalACInfo(mac: "AA:BB:CC:DD:EE:FF", ip: "192.168.1.50",
                                  key: "00112233445566778899aabbccddeeff", iv: "0f0e0d0c0b0a09080706050403020100"),
                      account: "local-d1")
        defer { Keychain.delete(account: "local-d1") }
        let root = ACKeysCard().environmentObject(ac).padding(16).frame(width: 380).background(.windowBackground)
        let host = NSHostingView(rootView: root)
        host.appearance = NSAppearance(named: .aqua)
        host.frame = NSRect(origin: .zero, size: host.fittingSize)
        let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { return }
        host.cacheDisplay(in: host.bounds, to: rep)
        try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: dir).appendingPathComponent("keys.png"))
    }
}
#endif
