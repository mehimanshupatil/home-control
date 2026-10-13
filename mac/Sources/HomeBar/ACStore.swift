import CryptoKit
import Foundation

/// JSON keys used for each AC capability; resolved from the device model, with app defaults.
struct ACKeys: Codable {
    var power = "ps"
    var mode = "acmd"
    var temp = "actmp"
    var fan = "acfs"
    var verticalSwing = "acvs"
    var room = "rmtmp"
}

/// `ki` constants from the app's BaseCapability.Type enum (cloud path; the LAN path always uses 1).
enum ACKeyIndex {
    static let mode = 0, power = 1, temp = 2, fan = 4, verticalSwing = 5
}

struct ACState {
    var power = false
    var mode = "cool"
    var setTemp = 24.0
    var roomTemp: Double?
    var fan = "auto"
    var online: Bool?
    /// Every key the AC has reported (local UDP, MQTT, REST), merged; drives the generic controls.
    var raw: [String: JSONValue] = [:]
}

enum ACTransport: String {
    case connecting, local, cloud, unavailable
}

struct ACDevice: Identifiable, Codable {
    let id: String
    var name: String
    var space: String
    let homeId: String
    let topic: String
    var modelId: String?
    var keys = ACKeys()
    /// nil = model unknown (try local anyway), false = model lacks `udpControl`.
    var udpCapable: Bool?
    var state = ACState()
    var transport = ACTransport.connecting
    /// Raw device-model capabilities: `{controlCapabilities, configCapabilities, statusCapabilitiesV2}`.
    var model: JSONValue?

    /// Capabilities this AC's model declares (empty when the model is unknown).
    var capabilities: [ACCapability] { ACCapability.parse(model: model) }

    enum CodingKeys: String, CodingKey { case id, name, space, homeId, topic, modelId, keys, udpCapable, model }
}

@MainActor
final class ACStore: ObservableObject {
    static let modes = ["auto", "cool", "dry", "fan", "heat"]
    static let fanSpeeds = ["auto", "quiet", "low", "medium", "high"]

    @Published private(set) var loggedIn = false
    @Published private(set) var devices: [ACDevice] = []
    @Published private(set) var loading = false
    @Published private(set) var mqttConnected = false
    @Published var errorMessage: String?
    @Published var localOnly: Bool {
        didSet {
            UserDefaults.standard.set(localOnly, forKey: "acLocalOnly")
            if localOnly { disconnectMQTT() }
            Task { await connectTransports() }
        }
    }

    private let api = MirAIeAPI()
    private let local = LocalACTransport()
    private var clients: [String: MQTTClient] = [:]
    private var connectedHomes = Set<String>()
    private var rediscoverTimer: Timer?

    var anyOn: Bool { devices.contains { $0.state.power } }

    init() {
        localOnly = UserDefaults.standard.bool(forKey: "acLocalOnly")
        local.onStatus = { [weak self] deviceId, json in
            MainActor.assumeIsolated { self?.applyStatus(json, deviceId: deviceId, fromLocal: true) }
        }
        if let data = UserDefaults.standard.data(forKey: "acDevices"),
           let cached = try? JSONDecoder().decode([ACDevice].self, from: data) {
            devices = cached
        }
        rediscoverTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.connectTransports() }
        }
        Task {
            loggedIn = await api.isLoggedIn || (localOnly && !devices.isEmpty)
            guard loggedIn else { return }
            if localOnly && !devices.isEmpty && devices.allSatisfy({ localInfo($0.id) != nil }) {
                await connectTransports()
            } else {
                await reload()
            }
        }
    }

    // MARK: Session

    func login(_ login: String, password: String) async {
        errorMessage = nil
        loading = true
        defer { loading = false }
        do {
            try await api.login(login, password: password)
            loggedIn = true
            await reload()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func logout() {
        disconnectMQTT()
        for d in devices { Keychain.delete(account: "local-\(d.id)") }
        devices = []
        UserDefaults.standard.removeObject(forKey: "acDevices")
        Task { await api.logout() }
        loggedIn = false
    }

    // MARK: Loading (cloud bootstrap)

    /// Full refresh from the cloud: homes, models, LAN keys, initial status. Then picks transports.
    func reload() async {
        loading = true
        defer { loading = false }
        do {
            guard let homes = try await api.get("homeManagement/homes") as? [[String: Any]] else {
                throw MirAIeError.badResponse
            }
            var list = parseDevices(homes)
            for i in list.indices {
                if let old = devices.first(where: { $0.id == list[i].id }) {
                    list[i].state = old.state
                    list[i].transport = old.transport
                    list[i].model = old.model
                }
                if let modelId = list[i].modelId {
                    let (keys, udp, raw) = await model(modelId)
                    if let raw {
                        list[i].keys = keys
                        list[i].udpCapable = udp
                        list[i].model = raw
                    } else if list[i].model == nil {
                        list[i].udpCapable = nil
                    }
                }
                if localInfo(list[i].id) == nil, list[i].udpCapable != false {
                    await fetchLocalInfo(deviceId: list[i].id)
                }
                if !localOnly,
                   let status = try? await api.get("deviceManagement/devices/\(list[i].id)/mobile/status") as? [String: Any] {
                    Self.apply(status, to: &list[i])
                }
            }
            devices = list
            if let data = try? JSONEncoder().encode(list) { UserDefaults.standard.set(data, forKey: "acDevices") }
            errorMessage = nil
        } catch MirAIeError.notLoggedIn {
            if devices.isEmpty { logout() } else { errorMessage = "Sign in again to refresh keys" }
        } catch {
            errorMessage = error.localizedDescription
        }
        await connectTransports()
    }

    private func parseDevices(_ homes: [[String: Any]]) -> [ACDevice] {
        var all: [(ACDevice, String)] = []
        for home in homes {
            guard let homeId = home["homeId"] as? String else { continue }
            for space in home["spaces"] as? [[String: Any]] ?? [] {
                let spaceName = space["spaceName"] as? String ?? ""
                for d in space["devices"] as? [[String: Any]] ?? [] {
                    guard let id = d["deviceId"] as? String else { continue }
                    let topic = (d["topic"] as? [String])?.first ?? "\(homeId)/\(id)"
                    let dev = ACDevice(id: id, name: d["deviceName"] as? String ?? "AC", space: spaceName,
                                       homeId: homeId, topic: topic, modelId: d["modelId"] as? String)
                    all.append((dev, (d["category"] as? String ?? "").uppercased()))
                }
            }
        }
        let acs = all.filter { $0.1 == "AC" || $0.1.hasPrefix("AC") || $0.1.contains("AIR") }
        return (acs.isEmpty ? all : acs).map(\.0)
    }

    private func model(_ modelId: String) async -> (ACKeys, Bool?, JSONValue?) {
        var keys = ACKeys()
        guard let model = try? await api.get("deviceManagement/deviceModels/modelId/\(modelId)") as? [String: Any] else {
            return (keys, nil, nil)
        }
        var rawCaps: [String: Any] = [:]
        for field in ["controlCapabilities", "configCapabilities", "statusCapabilitiesV2"] {
            if let v = model[field] { rawCaps[field] = v }
        }
        let caps = (model["controlCapabilities"] as? [[String: Any]] ?? [])
            + (model["statusCapabilitiesV2"] as? [[String: Any]] ?? [])
        for cap in caps {
            guard let name = cap["capability"] as? String else { continue }
            let key = ((cap["attributes"] as? [[String: Any]])?.first?["key"] as? String) ?? name
            switch name {
            case "PowerStatus": keys.power = key
            case "ACMode": keys.mode = key
            case "ACTemperature": keys.temp = key
            case "ACFanSpeed": keys.fan = key
            case "VerticalSwing": keys.verticalSwing = key
            case "RoomTemperature": keys.room = key
            default: break
            }
        }
        let config = model["configCapabilities"] as? [[String: Any]] ?? []
        let udp = config.contains { ($0["capability"] as? String) == "udpControl" }
        return (keys, udp, JSONValue(any: rawCaps))
    }

    private func localInfo(_ deviceId: String) -> LocalACInfo? {
        Keychain.load(LocalACInfo.self, account: "local-\(deviceId)")
    }

    /// One-time fetch of MAC, last IP and AES key/IV for LAN control.
    private func fetchLocalInfo(deviceId: String) async {
        guard let resp = try? await api.get("deviceManagement/devices/deviceId/\(deviceId)") else { return }
        let d = (resp as? [[String: Any]])?.first ?? (resp as? [String: Any])
        guard let d, let mac = d["macAddress"] as? String,
              let key = d["udpControlKey"] as? String, let iv = d["udpcontrolIV"] as? String,
              !key.isEmpty, !iv.isEmpty else { return }
        Keychain.save(LocalACInfo(mac: mac, ip: d["ipAddress"] as? String, key: key, iv: iv), account: "local-\(deviceId)")
    }

    // MARK: Diagnostics

    /// Shareable JSON describing each AC's model and last status. Never includes the password,
    /// tokens, or the full LAN key/IV (masked to 4 hex characters).
    func diagnosticsJSON() -> String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
        func mask(_ hex: String) -> String { String(hex.prefix(4)) + "…" }
        let acs: [JSONValue] = devices.map { d in
            var o: [String: JSONValue] = [
                "id": .string(d.id),
                "name": .string(d.name),
                "modelId": d.modelId.map(JSONValue.string) ?? .null,
                "udpCapable": d.udpCapable.map(JSONValue.bool) ?? .null,
                "transport": .string(d.transport.rawValue),
                "model": d.model ?? .null,
                "lastStatus": .object(d.state.raw),
                "parsedCapabilities": .array(d.capabilities.map { c in
                    .object(["name": .string(c.name), "key": .string(c.key), "ki": .number(Double(c.ki)),
                             "kind": .string(c.kindName), "values": .string(c.valuesSummary)])
                }),
            ]
            if let info = localInfo(d.id) {
                o["lan"] = .object(["mac": .string(info.mac), "ip": info.ip.map(JSONValue.string) ?? .null,
                                    "key": .string(mask(info.key)), "iv": .string(mask(info.iv))])
            }
            return .object(o)
        }
        return JSONValue.object([
            "app": .string("HomeBar \(version) (macOS)"),
            "localOnly": .bool(localOnly),
            "cloudConnected": .bool(mqttConnected),
            "acs": .array(acs),
        ]).prettyText()
    }

    // MARK: Key backup (sign-in-free local control)

    /// One portable entry per AC: everything needed to control it on the LAN without the cloud.
    struct ExportedAC: Codable {
        var id: String, name: String, space: String, homeId: String, topic: String, modelId: String?
        var keys: ACKeys
        var mac: String, ip: String?, key: String, iv: String
        /// Optional so codes from older versions (and the Android app) still import.
        var model: JSONValue?
    }

    static let keyCodePrefix = "miraie-local-v1:"

    /// ACs that have LAN keys, with their key details (for display).
    var exportableDevices: [(device: ACDevice, info: LocalACInfo)] {
        devices.compactMap { d in localInfo(d.id).map { (d, $0) } }
    }

    /// A single copyable code containing every AC's LAN key, or nil when no keys are stored.
    func exportKeyCode() -> String? {
        let entries = exportableDevices.map { d, info in
            ExportedAC(id: d.id, name: d.name, space: d.space, homeId: d.homeId, topic: d.topic, modelId: d.modelId,
                       keys: d.keys, mac: info.mac, ip: info.ip, key: info.key, iv: info.iv, model: d.model)
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys // same keys → same code
        guard !entries.isEmpty, let data = try? encoder.encode(entries) else { return nil }
        return Self.keyCodePrefix + data.base64EncodedString()
    }

    /// Restores ACs from a key code and switches to local-only control. No Panasonic sign-in needed.
    func importKeyCode(_ text: String) {
        errorMessage = nil
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix(Self.keyCodePrefix),
              let data = Data(base64Encoded: String(trimmed.dropFirst(Self.keyCodePrefix.count))),
              let entries = try? JSONDecoder().decode([ExportedAC].self, from: data), !entries.isEmpty else {
            errorMessage = "That isn't a valid AC key code."
            return
        }
        devices = entries.map { e in
            Keychain.save(LocalACInfo(mac: e.mac, ip: e.ip, key: e.key, iv: e.iv), account: "local-\(e.id)")
            var d = ACDevice(id: e.id, name: e.name, space: e.space, homeId: e.homeId, topic: e.topic, modelId: e.modelId)
            d.keys = e.keys
            d.udpCapable = true
            d.model = e.model
            return d
        }
        if let data = try? JSONEncoder().encode(devices) { UserDefaults.standard.set(data, forKey: "acDevices") }
        localOnly = true
        loggedIn = true
        Task { await connectTransports() }
    }

    /// Refresh button: full cloud reload when signed in, otherwise just re-find ACs on the LAN.
    func refresh() async {
        if await api.isLoggedIn {
            await reload()
        } else {
            loading = true
            await connectTransports()
            loading = false
        }
    }

    // MARK: Transport selection

    /// Tries LAN discovery for each device (~3 s); devices that don't answer fall back to MQTT unless local-only.
    func connectTransports() async {
        await withTaskGroup(of: (String, String?).self) { group in
            for d in devices where d.udpCapable != false {
                guard let info = localInfo(d.id) else { continue }
                local.register(deviceId: d.id, info: info)
                group.addTask { [local] in (d.id, await local.discover(deviceId: d.id)) }
            }
            for await (id, ip) in group {
                guard let i = devices.firstIndex(where: { $0.id == id }) else { continue }
                if let ip {
                    devices[i].transport = .local
                    devices[i].state.online = true
                    if var info = localInfo(id), info.ip != ip {
                        info.ip = ip
                        Keychain.save(info, account: "local-\(id)")
                    }
                } else {
                    devices[i].transport = .connecting
                }
            }
        }
        for i in devices.indices where devices[i].transport != .local {
            devices[i].transport = localOnly ? .unavailable : .cloud
        }
        if localOnly {
            if let err = local.bindError { errorMessage = err }
            return
        }
        await connectMQTT()
    }

    private func connectMQTT() async {
        let cloudHomes = Set(devices.filter { $0.transport == .cloud }.map(\.homeId))
        guard !cloudHomes.isEmpty else { disconnectMQTT(); return }
        guard let token = try? await api.validAccessToken(), let userId = await api.userId else { return }
        for homeId in cloudHomes {
            let client = clients[homeId] ?? makeClient(homeId: homeId, userId: userId)
            for d in devices where d.homeId == homeId { client.subscribe(d.topic + "/status") }
            if !connectedHomes.contains(homeId) { client.connect(username: homeId, password: token) }
        }
    }

    private func disconnectMQTT() {
        clients.values.forEach { $0.disconnect() }
        clients.removeAll()
        connectedHomes.removeAll()
        mqttConnected = false
    }

    private func makeClient(homeId: String, userId: String) -> MQTTClient {
        let hash = SHA256.hash(data: Data("\(userId)##\(homeId)".utf8)).map { String(format: "%02x", $0) }.joined()
        let suffix = String(format: "%05d", Int.random(in: 0..<100_000))
        let client = MQTTClient(host: "mqtt.miraie.in", port: 8883, clientId: "AN" + hash.prefix(16) + suffix)
        client.onMessage = { [weak self] topic, payload in
            MainActor.assumeIsolated {
                guard let self, let json = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
                      let d = self.devices.first(where: { topic == $0.topic + "/status" }) else { return }
                self.applyStatus(json, deviceId: d.id, fromLocal: false)
            }
        }
        client.onConnectionChange = { [weak self] up in
            MainActor.assumeIsolated {
                guard let self else { return }
                if up { self.connectedHomes.insert(homeId) } else { self.connectedHomes.remove(homeId) }
                self.mqttConnected = !self.connectedHomes.isEmpty
            }
        }
        client.onAuthFailure = { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                Task {
                    do {
                        try await self.api.renewSession()
                        self.connectedHomes.remove(homeId)
                        await self.connectMQTT()
                    } catch {
                        self.errorMessage = "MQTT auth failed: \(error.localizedDescription)"
                    }
                }
            }
        }
        clients[homeId] = client
        return client
    }

    // MARK: Status

    private func applyStatus(_ json: [String: Any], deviceId: String, fromLocal: Bool) {
        guard let i = devices.firstIndex(where: { $0.id == deviceId }) else { return }
        // Ignore cloud echoes for devices driven locally; their LAN status is authoritative.
        if !fromLocal && devices[i].transport == .local { return }
        Self.apply(json, to: &devices[i])
        if fromLocal { devices[i].state.online = true }
    }

    private static func apply(_ json: [String: Any], to device: inout ACDevice) {
        for (key, value) in json { device.state.raw[key] = JSONValue(any: value) }
        let k = device.keys
        if let v = json[k.power] { device.state.power = string(v) == "on" }
        if let v = json[k.mode] { device.state.mode = string(v) }
        if let v = json[k.temp], let t = double(v) { device.state.setTemp = t }
        if let v = json[k.room], let t = double(v) { device.state.roomTemp = t }
        if let v = json[k.fan] { device.state.fan = string(v) }
        if let v = json["onlineStatus"] {
            device.state.online = (v as? Bool) ?? (string(v).lowercased() == "true")
        }
    }

    private static func string(_ v: Any) -> String { (v as? String) ?? "\(v)" }
    private static func double(_ v: Any) -> Double? { (v as? Double) ?? Double(string(v)) }

    // MARK: Controls

    private func send(_ device: ACDevice, key: String, value: Any, ki: Int) {
        switch device.transport {
        case .local:
            let body: [String: Any] = [key: value, "ki": 1, "cnt": "an", "sid": "1"]
            if !local.send(deviceId: device.id, body) { errorMessage = "Local send failed" }
        case .cloud:
            guard let client = clients[device.homeId] else { return }
            let body: [String: Any] = [key: value, "ki": ki, "cnt": "an", "sid": "1"]
            guard let data = try? JSONSerialization.data(withJSONObject: body) else { return }
            client.publish(device.topic + "/control", data)
        case .connecting, .unavailable:
            errorMessage = "\(device.name) is not reachable"
        }
    }

    private func update(_ device: ACDevice, _ change: (inout ACState) -> Void) {
        guard let i = devices.firstIndex(where: { $0.id == device.id }) else { return }
        change(&devices[i].state)
    }

    func setPower(_ d: ACDevice, _ on: Bool) {
        update(d) { $0.power = on }
        send(d, key: d.keys.power, value: on ? "on" : "off", ki: ACKeyIndex.power)
    }

    func setTemp(_ d: ACDevice, _ t: Double) {
        let clamped = min(30, max(16, (t * 2).rounded() / 2))
        update(d) { $0.setTemp = clamped }
        let text = clamped == clamped.rounded() ? String(Int(clamped)) : String(format: "%.1f", clamped)
        send(d, key: d.keys.temp, value: text, ki: ACKeyIndex.temp)
    }

    func setMode(_ d: ACDevice, _ mode: String) {
        update(d) { $0.mode = mode }
        send(d, key: d.keys.mode, value: mode, ki: ACKeyIndex.mode)
    }

    /// Generic control for any model capability. Stored status updates optimistically.
    func send(_ d: ACDevice, capability cap: ACCapability, value: Any) {
        guard let i = devices.firstIndex(where: { $0.id == d.id }) else { return }
        devices[i].state.raw[cap.key] = JSONValue(any: value)
        send(devices[i], key: cap.key, value: value, ki: cap.ki)
    }

    func setFan(_ d: ACDevice, _ fan: String) {
        update(d) { $0.fan = fan }
        send(d, key: d.keys.fan, value: fan, ki: ACKeyIndex.fan)
    }
}

#if DEBUG
extension ACStore {
    /// Sample data for UI snapshots (`HOMEBAR_SNAPSHOT`).
    func seedDemo() {
        loggedIn = true
        var bedroom = ACDevice(id: "d1", name: "Bedroom AC", space: "Bedroom", homeId: "h", topic: "h/d1", modelId: nil)
        bedroom.state = ACState(power: true, mode: "cool", setTemp: 24, roomTemp: 28, fan: "auto", online: true)
        bedroom.transport = .local
        bedroom.modelId = "demo-model"
        bedroom.model = try? JSONDecoder().decode(JSONValue.self, from: Data(Self.demoModel.utf8))
        bedroom.state.raw = ["ps": .string("on"), "acmd": .string("cool"), "actmp": .number(24), "rmtmp": .number(28),
                             "acfs": .string("auto"), "acvs": .number(0), "achs": .number(3), "acdc": .string("on"),
                             "acec": .string("off"), "acpm": .string("on"), "acng": .string("on"), "accnv": .number(80),
                             "rmhmd": .number(62), "acfdl": .number(2), "accpc": .number(840), "errors": .string("H11")]
        var living = ACDevice(id: "d2", name: "Living Room AC", space: "Living Room", homeId: "h", topic: "h/d2", modelId: nil)
        living.state = ACState(power: false, mode: "dry", setTemp: 25.5, roomTemp: 30, fan: "low", online: true)
        living.transport = .cloud
        devices = [bedroom, living]
    }

    static let demoModel = """
    {"controlCapabilities":[
      {"capability":"PowerStatus","attributes":[{"key":"ps","values":["on","off"]}]},
      {"capability":"ACMode","attributes":[{"key":"acmd","values":["auto","cool","dry","fan","heat"]}]},
      {"capability":"ACTemperature","attributes":[{"key":"actmp"}]},
      {"capability":"ACFanSpeed","attributes":[{"key":"acfs"}]},
      {"capability":"VerticalSwing","attributes":[{"key":"acvs","range":{"min":[{"value":0}],"max":[{"value":5}],"increment":[{"value":1}]}}]},
      {"capability":"HorizontalSwing","attributes":[{"key":"achs","range":{"min":[{"value":0}],"max":[{"value":5}],"increment":[{"value":1}]}}]},
      {"capability":"DisplayControl","attributes":[{"key":"acdc","values":["on","night","off"]}]},
      {"capability":"ECOMode","attributes":[{"key":"acec","values":["on","off"]}]},
      {"capability":"PowerfulMode","attributes":[{"key":"acpm","values":["on","off"]}]},
      {"capability":"EvaporatorClean","attributes":[{"key":"acecl","values":["on","off"]}]},
      {"capability":"NanoeG","attributes":[{"key":"acng","values":["on","off"]}]},
      {"capability":"BuzzerControl","attributes":[{"key":"acbz","values":["on","off"]}]},
      {"capability":"Convertible","attributes":[{"key":"accnv","values":["40","55","70","80","90","100","110","OFF"]}]}
    ],
    "configCapabilities":[{"capability":"udpControl","attributes":[{"key":"udp"}]}],
    "statusCapabilitiesV2":[
      {"capability":"RoomTemperature","attributes":[{"key":"rmtmp"}]},
      {"capability":"RoomHumidity","attributes":[{"key":"rmhmd"}]},
      {"capability":"FilterDustLevel","attributes":[{"key":"acfdl","range":{"min":[{"value":0}],"max":[{"value":5}]}}]},
      {"capability":"CurrentPowerConsumption","attributes":[{"key":"accpc"}]},
      {"capability":"ACFaults","attributes":[{"key":"errors"}]}
    ]}
    """
}
#endif
