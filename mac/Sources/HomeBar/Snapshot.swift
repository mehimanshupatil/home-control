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
        BulbCard.debugExpandFirst = true
        for (tab, tabName) in [(0, "climate"), (1, "lights")] {
            for (appearance, mode) in [(NSAppearance.Name.aqua, "light"), (.darkAqua, "dark")] {
                UserDefaults.standard.set(tab, forKey: "selectedTab")
                let ac = ACStore()
                let lights = LightsStore()
                RunLoop.main.run(until: Date().addingTimeInterval(0.3)) // let startup tasks settle
                ac.seedDemo(); lights.seedDemo()
                let root = ContentView()
                    .environmentObject(ac)
                    .environmentObject(lights)
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
            let root = view.environmentObject(ac).environmentObject(lights)
                .padding(16).frame(width: 380).background(.windowBackground)
            let host = NSHostingView(rootView: root)
            host.appearance = NSAppearance(named: appearance)
            host.frame = NSRect(origin: .zero, size: host.fittingSize)
            let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
            window.appearance = NSAppearance(named: appearance)
            window.contentView = host
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
        ACCard.debugExpandMore = true
        BulbCard.debugExpandFirst = true
        render(ACCard(device: ac.devices[0]), name: "ac-more", dir: dir, ac: ac, lights: lights)
        render(ACCard(device: ac.devices[1]), name: "ac-more-off-unknown", dir: dir, ac: ac, lights: lights)
        render(ACDiagnosticsCard {}, name: "diagnostics", dir: dir, ac: ac, lights: lights)
        render(BulbCard(bulb: lights.bulbs[0]), name: "bulb-scene", dir: dir, ac: ac, lights: lights)
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
        print("diagnostics has model + status:", json.contains("controlCapabilities") && json.contains("rmhmd"))
    }

    private static func renderKeysCard(dir: String) {
        let ac = ACStore()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        ac.seedDemo()
        Keychain.save(LocalACInfo(mac: "AA:BB:CC:DD:EE:FF", ip: "192.168.1.50",
                                  key: "00112233445566778899aabbccddeeff", iv: "0f0e0d0c0b0a09080706050403020100"),
                      account: "local-d1")
        defer { Keychain.delete(account: "local-d1") }
        let root = ACKeysCard {}.environmentObject(ac).padding(16).frame(width: 380).background(.windowBackground)
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
