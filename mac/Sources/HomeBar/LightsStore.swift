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
        (33, "Diwali"), (35, "Alarm"), // ids from the WiZ app's bundled light-mode library
    ]

    /// Scene ids grouped as the WiZ app presents them: static whites, static colours, moving (dynamic).
    nonisolated static let sceneGroups: [(title: String, ids: [Int])] = [
        ("White", [11, 12, 13, 14, 15, 16, 17, 18, 30]),
        ("Colors", [6, 19]),
        ("Moving", [1, 2, 3, 4, 5, 7, 8, 9, 10, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 31, 32, 33, 35]),
    ]
    nonisolated static let movingScenes = Set(sceneGroups[2].ids)

    static func sceneName(_ id: Int) -> String? { scenes.first { $0.id == id }?.name }

    @Published private(set) var bulbs: [Bulb] = []
    @Published private(set) var scanning = false
    /// Local rooms (bulb MACs); a bulb belongs to at most one.
    @Published private(set) var groups: [LightGroup] = LightsStore.load("wizGroups") ?? []
    /// Saved light states, replayed with setPilot.
    @Published private(set) var presets: [LightPreset] = LightsStore.load("wizPresets") ?? []

    private var names: [String: String] {
        get { UserDefaults.standard.dictionary(forKey: "wizNames") as? [String: String] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: "wizNames") }
    }
    private var timer: Timer?

    var anyOn: Bool { bulbs.contains { $0.on } }

    init() {
        #if DEBUG
        if ProcessInfo.processInfo.environment["HOMEBAR_SNAPSHOT"] != nil { groups = []; presets = []; return }
        #endif
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

    /// Identify: the WiZ app's `pulse` (`PulseParam` delta/duration). If the bulb doesn't answer within
    /// 1 s, blinks it off/on twice ~400 ms apart instead and leaves it as it was.
    func identify(_ b: Bulb) {
        let ip = b.ip
        let original = b.on
        Task.detached {
            let reply = WizUDP.request(["id": 1, "method": "pulse", "params": ["delta": -60, "duration": 800]], ip: ip, timeout: 1)
            let ok = (reply?["result"] as? [String: Any])?["success"] as? Bool ?? false
            if ok { return }
            for state in [!original, original, !original, original] {
                _ = WizUDP.request(["id": 1, "method": "setPilot", "params": ["state": state]], ip: ip)
                try? await Task.sleep(for: .milliseconds(400))
            }
        }
    }

    func setAll(_ on: Bool) {
        for b in bulbs where b.reachable { setOn(b, on) }
    }

    // MARK: Rooms (local groups)

    func members(_ g: LightGroup) -> [Bulb] { bulbs.filter { g.members.contains($0.id) } }
    func group(of b: Bulb) -> LightGroup? { groups.first { $0.members.contains(b.id) } }
    var ungrouped: [Bulb] { bulbs.filter { group(of: $0) == nil } }

    @discardableResult
    func createGroup(_ name: String) -> LightGroup? {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        let g = LightGroup(name: trimmed, members: [])
        groups.append(g)
        saveGroups()
        return g
    }

    func renameGroup(_ g: LightGroup, _ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let i = groups.firstIndex(where: { $0.id == g.id }) else { return }
        groups[i].name = trimmed
        saveGroups()
    }

    func deleteGroup(_ g: LightGroup) {
        groups.removeAll { $0.id == g.id }
        saveGroups()
    }

    /// Moves a bulb into `g` (nil = no room).
    func assign(_ b: Bulb, to g: LightGroup?) {
        for i in groups.indices { groups[i].members.removeAll { $0 == b.id } }
        if let g, let i = groups.firstIndex(where: { $0.id == g.id }) { groups[i].members.append(b.id) }
        saveGroups()
    }

    func setGroupOn(_ g: LightGroup, _ on: Bool) { for b in members(g) where b.reachable { setOn(b, on) } }
    func setGroupDimming(_ g: LightGroup, _ v: Int) { for b in members(g) where b.reachable { setDimming(b, v) } }
    func setGroupScene(_ g: LightGroup, _ id: Int) { for b in members(g) where b.reachable { setScene(b, id) } }

    // MARK: Presets

    /// Saves the current state of the given bulbs under `name` (replacing a preset with that name).
    func savePreset(_ name: String, bulbs targets: [Bulb]) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !targets.isEmpty else { return }
        var entries: [String: PilotState] = [:]
        for b in targets { entries[b.id] = PilotState(b) }
        presets.removeAll { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }
        presets.append(LightPreset(name: trimmed, entries: entries))
        Self.store(presets, "wizPresets")
    }

    func deletePreset(_ p: LightPreset) {
        presets.removeAll { $0.id == p.id }
        Self.store(presets, "wizPresets")
    }

    func applyPreset(_ p: LightPreset) {
        for b in bulbs where b.reachable {
            guard let s = p.entries[b.id] else { continue }
            setPilot(b, s.params) { s.apply(to: &$0) }
        }
    }

    private func saveGroups() { Self.store(groups, "wizGroups") }

    private static func load<T: Decodable>(_ key: String) -> T? {
        UserDefaults.standard.data(forKey: key).flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }

    private static func store<T: Encodable>(_ value: T, _ key: String) {
        if let data = try? JSONEncoder().encode(value) { UserDefaults.standard.set(data, forKey: key) }
    }
}

/// A local room of WiZ bulbs (stored on this Mac only).
struct LightGroup: Codable, Identifiable, Equatable {
    var id = UUID()
    var name: String
    var members: [String] // bulb MACs
}

/// One bulb's light state, as sent with setPilot.
struct PilotState: Codable, Equatable {
    var on: Bool
    var dimming: Int
    var temp: Int?
    var r: Int?, g: Int?, b: Int?
    var sceneId: Int
    var speed: Int

    init(_ bulb: Bulb) {
        on = bulb.on
        dimming = bulb.dimming
        temp = bulb.temp
        r = bulb.rgb?.r; g = bulb.rgb?.g; b = bulb.rgb?.b
        sceneId = bulb.sceneId
        speed = bulb.speed
    }

    var params: [String: Any] {
        guard on else { return ["state": false] }
        var p: [String: Any] = ["state": true, "dimming": dimming]
        if sceneId > 0 {
            p["sceneId"] = sceneId
            if LightsStore.movingScenes.contains(sceneId) { p["speed"] = speed }
        } else if let r, let g, let b {
            p["r"] = r; p["g"] = g; p["b"] = b
        } else if let temp {
            p["temp"] = temp
        }
        return p
    }

    func apply(to bulb: inout Bulb) {
        bulb.on = on
        guard on else { return }
        bulb.dimming = dimming
        bulb.sceneId = sceneId
        bulb.speed = speed
        if let r, let g, let b { bulb.rgb = (r, g, b); bulb.temp = nil } else if let temp { bulb.temp = temp; bulb.rgb = nil }
    }
}

/// A named set of bulb states (one bulb or a whole room).
struct LightPreset: Codable, Identifiable, Equatable {
    var id = UUID()
    var name: String
    var entries: [String: PilotState]
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
        groups = [LightGroup(name: "Living room", members: ["a2", "a3"]), LightGroup(name: "Study", members: ["a1"])]
        presets = [
            LightPreset(name: "Movie", entries: ["a2": PilotState(ceiling), "a3": PilotState(hall)]),
            LightPreset(name: "Reading", entries: ["a1": PilotState(desk)]),
        ]
    }
}
#endif
