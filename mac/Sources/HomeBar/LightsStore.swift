import Foundation

struct Bulb: Identifiable, Equatable {
    let id: String // MAC
    var ip: String
    var name: String
    var on = false
    var dimming = 100
    var temp: Int?
    var rgb: (r: Int, g: Int, b: Int)?
    var sceneId = 0
    var speed = 100
    var reachable = true

    static func == (a: Bulb, b: Bulb) -> Bool {
        a.id == b.id && a.ip == b.ip && a.name == b.name && a.on == b.on && a.dimming == b.dimming
            && a.temp == b.temp && a.rgb?.r == b.rgb?.r && a.rgb?.g == b.rgb?.g && a.rgb?.b == b.rgb?.b
            && a.sceneId == b.sceneId && a.speed == b.speed && a.reachable == b.reachable
    }
}

@MainActor
final class LightsStore: ObservableObject {
    static let scenes: [(id: Int, name: String)] = [
        (1, "Ocean"), (2, "Romance"), (3, "Sunset"), (4, "Party"), (5, "Fireplace"), (6, "Cozy"),
        (7, "Forest"), (8, "Pastel colors"), (9, "Wake up"), (10, "Bedtime"), (11, "Warm white"),
        (12, "Daylight"), (13, "Cool white"), (14, "Night light"), (15, "Focus"), (16, "Relax"),
        (17, "True colors"), (18, "TV time"), (19, "Plant growth"), (20, "Spring"), (21, "Summer"),
        (22, "Fall"), (23, "Deep dive"), (24, "Jungle"), (25, "Mojito"), (26, "Club"), (27, "Christmas"),
        (28, "Halloween"), (29, "Candlelight"), (30, "Golden white"), (31, "Pulse"), (32, "Steampunk"),
    ]

    /// Scene ids grouped as the WiZ app presents them: static whites, static colours, moving (dynamic).
    static let sceneGroups: [(title: String, ids: [Int])] = [
        ("White", [11, 12, 13, 14, 15, 16, 17, 18, 30]),
        ("Colors", [6, 19]),
        ("Moving", [1, 2, 3, 4, 5, 7, 8, 9, 10, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 31, 32]),
    ]
    static let movingScenes = Set(sceneGroups[2].ids)

    static func sceneName(_ id: Int) -> String? { scenes.first { $0.id == id }?.name }

    @Published private(set) var bulbs: [Bulb] = []
    @Published private(set) var scanning = false

    private var names: [String: String] {
        get { UserDefaults.standard.dictionary(forKey: "wizNames") as? [String: String] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: "wizNames") }
    }
    private var timer: Timer?

    var anyOn: Bool { bulbs.contains { $0.on } }

    init() {
        Task { await discover() }
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.discover() }
        }
    }

    func discover() async {
        guard !scanning else { return }
        scanning = true
        defer { scanning = false }
        let replies = await Task.detached { WizUDP.broadcast(["method": "getPilot", "params": [:]]) }.value
        var seen = Set<String>()
        for reply in replies {
            guard let result = reply.json["result"] as? [String: Any], let mac = result["mac"] as? String else { continue }
            seen.insert(mac)
            var bulb = bulbs.first { $0.id == mac } ?? Bulb(id: mac, ip: reply.ip, name: names[mac] ?? "Bulb \(mac.suffix(4))")
            bulb.ip = reply.ip
            bulb.reachable = true
            Self.apply(result, to: &bulb)
            if let i = bulbs.firstIndex(where: { $0.id == mac }) { bulbs[i] = bulb } else { bulbs.append(bulb) }
        }
        for i in bulbs.indices where !seen.contains(bulbs[i].id) { bulbs[i].reachable = false }
        bulbs.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private static func apply(_ r: [String: Any], to b: inout Bulb) {
        if let s = r["state"] as? Bool { b.on = s }
        if let d = r["dimming"] as? Int { b.dimming = d }
        b.sceneId = r["sceneId"] as? Int ?? 0
        if let sp = r["speed"] as? Int { b.speed = sp }
        if let t = r["temp"] as? Int, t > 0 {
            b.temp = t
            b.rgb = nil
        } else if let red = r["r"] as? Int, let g = r["g"] as? Int, let bl = r["b"] as? Int {
            b.rgb = (red, g, bl)
            b.temp = nil
        }
    }

    func rename(_ bulb: Bulb, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let i = bulbs.firstIndex(where: { $0.id == bulb.id }) else { return }
        bulbs[i].name = trimmed
        var n = names
        n[bulb.id] = trimmed
        names = n
    }

    // MARK: Controls

    private func setPilot(_ bulb: Bulb, _ params: [String: Any], local: (inout Bulb) -> Void) {
        guard let i = bulbs.firstIndex(where: { $0.id == bulb.id }) else { return }
        local(&bulbs[i])
        let ip = bulb.ip
        Task.detached {
            _ = WizUDP.request(["id": 1, "method": "setPilot", "params": params], ip: ip)
        }
    }

    func setOn(_ b: Bulb, _ on: Bool) { setPilot(b, ["state": on]) { $0.on = on } }

    func setDimming(_ b: Bulb, _ value: Int) {
        let v = min(100, max(10, value))
        setPilot(b, ["state": true, "dimming": v]) { $0.dimming = v; $0.on = true }
    }

    func setTemp(_ b: Bulb, _ kelvin: Int) {
        let k = min(6500, max(2200, kelvin))
        setPilot(b, ["state": true, "temp": k, "dimming": b.dimming]) {
            $0.temp = k; $0.rgb = nil; $0.sceneId = 0; $0.on = true
        }
    }

    func setColor(_ b: Bulb, r: Int, g: Int, b blue: Int) {
        setPilot(b, ["state": true, "r": r, "g": g, "b": blue, "dimming": b.dimming]) {
            $0.rgb = (r, g, blue); $0.temp = nil; $0.sceneId = 0; $0.on = true
        }
    }

    func setScene(_ b: Bulb, _ sceneId: Int) {
        setPilot(b, ["state": true, "sceneId": sceneId, "dimming": b.dimming]) {
            $0.sceneId = sceneId; $0.on = true
        }
    }

    /// Speed for moving scenes (10–200); the bulb ignores it for static scenes.
    func setSpeed(_ b: Bulb, _ speed: Int) {
        let v = min(200, max(10, speed))
        guard b.sceneId > 0 else { return }
        setPilot(b, ["state": true, "sceneId": b.sceneId, "speed": v]) { $0.speed = v }
    }

    /// Blinks the bulb (off/on twice, ~400 ms apart) and leaves it as it was. Uses setPilot only.
    func identify(_ b: Bulb) {
        let ip = b.ip
        let original = b.on
        Task.detached {
            for state in [!original, original, !original, original] {
                _ = WizUDP.request(["id": 1, "method": "setPilot", "params": ["state": state]], ip: ip)
                try? await Task.sleep(for: .milliseconds(400))
            }
        }
    }

    func setAll(_ on: Bool) {
        for b in bulbs where b.reachable { setOn(b, on) }
    }
}

#if DEBUG
extension LightsStore {
    func seedDemo() {
        var desk = Bulb(id: "a1", ip: "192.168.1.21", name: "Desk Lamp")
        desk.on = true; desk.dimming = 80; desk.temp = 2700
        var ceiling = Bulb(id: "a2", ip: "192.168.1.22", name: "Ceiling")
        ceiling.on = true; ceiling.dimming = 60; ceiling.sceneId = 4; ceiling.speed = 120
        var hall = Bulb(id: "a3", ip: "192.168.1.23", name: "Hallway")
        hall.on = false
        var porch = Bulb(id: "a4", ip: "192.168.1.24", name: "Porch")
        porch.reachable = false
        bulbs = [ceiling, desk, hall, porch]
        scanning = false
    }
}
#endif
