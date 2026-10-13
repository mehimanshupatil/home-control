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
    /// MirAIe room ("space") id; needed for rename, move and sleep-profile endpoints.
    var spaceId: String?
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

    enum CodingKeys: String, CodingKey { case id, name, space, homeId, spaceId, topic, modelId, keys, udpCapable, model }
}

@MainActor
final class ACStore: ObservableObject {
    nonisolated static let modes = ["auto", "cool", "dry", "fan", "heat"]
    nonisolated static let fanSpeeds = ["auto", "quiet", "low", "medium", "high"]

    @Published private(set) var loggedIn = false
    @Published var devices: [ACDevice] = []
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

    /// Experimental: fetch LAN keys and try discovery even when the model lacks `udpControl`.
    @Published var tryLocal: Bool {
        didSet {
            UserDefaults.standard.set(tryLocal, forKey: "acTryLocal")
            Task { await refresh() }
        }
    }
    /// Per AC: "answered", "timeout", "no key" or "not attempted" (for diagnostics).
    @Published private(set) var lastLocalDiscovery: [String: String] = [:]
    /// Saved local profiles per AC id.
    @Published private(set) var profiles: [String: [ACProfile]] = [:]
    /// True when a MirAIe session exists (cloud-only features need it, see `cloudBlocker`).
    @Published private(set) var cloudSignedIn = false
    /// Rooms per home id, from the last cloud reload (for "Move to room").
    @Published private(set) var homeSpaces: [String: [ACSpace]] = [:]
    /// Cloud sleep profiles per AC id (`sleepProfile` endpoints).
    @Published var sleepProfiles: [String: [SleepProfile]] = [:]
    /// Schedules by rule id (`rms` rules), for profiles' `smartRuleId`.
    @Published var sleepSchedules: [String: SleepSchedule] = [:]
    /// Notification wall state.
    @Published var notifications: [ACNotification] = []
    @Published var unreadAlerts = 0

    let api = MirAIeAPI()
    private let local = LocalACTransport()
    private var clients: [String: MQTTClient] = [:]
    private var connectedHomes = Set<String>()
    private var rediscoverTimer: Timer?

    var anyOn: Bool { devices.contains { $0.state.power } }

    #if DEBUG
    /// Every control message's fields, in order (self-checks; nothing is sent in harness runs).
    var sentLog: [[String: Any]] = []
    #endif

    init() {
        localOnly = UserDefaults.standard.bool(forKey: "acLocalOnly")
        tryLocal = UserDefaults.standard.bool(forKey: "acTryLocal")
        local.onStatus = { [weak self] deviceId, json in
            MainActor.assumeIsolated { self?.applyStatus(json, deviceId: deviceId, fromLocal: true) }
        }
        if let data = UserDefaults.standard.data(forKey: "acDevices"),
           let cached = try? JSONDecoder().decode([ACDevice].self, from: data) {
            devices = cached
            for d in cached { profiles[d.id] = Self.loadProfiles(d.id) }
        }
        #if DEBUG
        // Snapshot/self-check runs use sample data only: never touch the real session, cloud or LAN.
        let env = ProcessInfo.processInfo.environment
        if env["HOMEBAR_SNAPSHOT"] != nil || env["HOMEBAR_KEYTEST"] != nil { return }
        #endif
        rediscoverTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.connectTransports() }
        }
        Task {
            cloudSignedIn = await api.isLoggedIn
            loggedIn = cloudSignedIn || (localOnly && !devices.isEmpty)
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
            cloudSignedIn = true
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
        cloudSignedIn = false
        sleepProfiles = [:]
        sleepSchedules = [:]
        notifications = []
        unreadAlerts = 0
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
            homeSpaces = Self.parseSpaces(homes)
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
                profiles[list[i].id] = Self.loadProfiles(list[i].id)
                if localInfo(list[i].id) == nil, list[i].udpCapable != false || tryLocal {
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
            cloudSignedIn = true
            if !localOnly {
                Task { await self.loadCloudExtras() }
            }
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
                                       homeId: homeId, spaceId: space["spaceId"] as? String,
                                       topic: topic, modelId: d["modelId"] as? String)
                    all.append((dev, (d["category"] as? String ?? "").uppercased()))
                }
            }
        }
        let acs = all.filter { $0.1 == "AC" || $0.1.hasPrefix("AC") || $0.1.contains("AIR") }
        return (acs.isEmpty ? all : acs).map(\.0)
    }

    private static func parseSpaces(_ homes: [[String: Any]]) -> [String: [ACSpace]] {
        var out: [String: [ACSpace]] = [:]
        for home in homes {
            guard let homeId = home["homeId"] as? String else { continue }
            out[homeId] = (home["spaces"] as? [[String: Any]] ?? []).compactMap { s in
                guard let id = s["spaceId"] as? String else { return nil }
                return ACSpace(id: id, name: s["spaceName"] as? String ?? "Room")
            }
        }
        return out
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
              let key = d["udpControlKey"] as? String, let iv = (d["udpControlIV"] as? String) ?? (d["udpcontrolIV"] as? String), // server sends udpControlIV; the official app reads udpcontrolIV
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
                "firmware": d.state.raw["V"] ?? .null,
                "lanKeyAvailable": .bool(localInfo(d.id) != nil),
                "lastLocalDiscovery": .string(lastLocalDiscovery[d.id] ?? "not attempted"),
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
            "tryLocalExperimental": .bool(tryLocal),
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
        var results: [String: String] = [:]
        await withTaskGroup(of: (String, String?).self) { group in
            for d in devices {
                guard d.udpCapable != false || tryLocal else {
                    results[d.id] = "not attempted"
                    continue
                }
                guard let info = localInfo(d.id) else {
                    results[d.id] = "no key"
                    continue
                }
                local.register(deviceId: d.id, info: info)
                group.addTask { [local] in (d.id, await local.discover(deviceId: d.id)) }
            }
            for await (id, ip) in group {
                results[id] = ip == nil ? "timeout" : "answered"
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
        lastLocalDiscovery = results
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
        sendFields(device, [key: value], ki: ki)
    }

    /// Sends one control message. `fields` are the capability fields (e.g. `["acsp": "on", "pid": …]`);
    /// the envelope is added per transport: LAN `ki 1, cnt an, sid 1`; MQTT `ki n, cnt an, sid 1`;
    /// REST fallback (MQTT down) `ki n, cnt gva, sid 11` as in `GeofenceBroadcastReceiver`.
    /// `ki < 0` omits `ki` on the cloud paths (the official app sends nanoe GA without it).
    func sendFields(_ device: ACDevice, _ fields: [String: Any], ki: Int) {
        #if DEBUG
        sentLog.append(fields)
        if Self.isHarness { return }
        #endif
        switch device.transport {
        case .local:
            let body = fields.merging(["ki": 1, "cnt": "an", "sid": "1"]) { a, _ in a }
            if !local.send(deviceId: device.id, body) { errorMessage = "Local send failed" }
        case .cloud:
            if let client = clients[device.homeId], connectedHomes.contains(device.homeId) {
                var body = fields.merging(["cnt": "an", "sid": "1"]) { a, _ in a }
                if ki >= 0 { body["ki"] = ki }
                guard let data = try? JSONSerialization.data(withJSONObject: body) else { return }
                client.publish(device.topic + "/control", data)
            } else {
                sendREST(device, fields, ki: ki)
            }
        case .connecting, .unavailable:
            errorMessage = "\(device.name) is not reachable"
        }
    }

    /// REST control (`POST deviceManagement/control`), used when MQTT isn't connected.
    private func sendREST(_ device: ACDevice, _ fields: [String: Any], ki: Int) {
        guard !localOnly else { errorMessage = "\(device.name) is not reachable"; return }
        var params = fields.merging(["cnt": "gva", "sid": "11"]) { a, _ in a }
        if ki >= 0 { params["ki"] = ki }
        let body: [String: Any] = ["deviceId": device.id, "control_params": params]
        Task {
            do {
                try await api.request("POST", "deviceManagement/control", body: body)
            } catch {
                errorMessage = "Cloud control failed: \(error.localizedDescription)"
            }
        }
    }

    func update(_ device: ACDevice, _ change: (inout ACState) -> Void) {
        guard let i = devices.firstIndex(where: { $0.id == device.id }) else { return }
        change(&devices[i].state)
    }

    /// Mirrors `ACDevice.setPowerStatus`: turning on (mode ≠ fan) raises a target below 24° to 24°,
    /// and any power change ends a non-default sleep profile.
    func setPower(_ d: ACDevice, _ on: Bool) {
        update(d) { $0.power = on }
        send(d, key: d.keys.power, value: on ? "on" : "off", ki: ACKeyIndex.power)
        if on, d.state.mode != "fan", d.state.setTemp < 24, let c = current(d) {
            setTemp(c, 24)
        }
        resetSleepProfileIfNeeded(d)
    }

    func current(_ d: ACDevice) -> ACDevice? { devices.first { $0.id == d.id } }

    func setTemp(_ d: ACDevice, _ t: Double) {
        let r = d.tempRange
        let stepped = (t / r.step).rounded() * r.step
        let clamped = min(r.max, max(r.min, stepped))
        update(d) { $0.setTemp = clamped }
        let text = String(format: "%.1f", clamped) // official app uses DecimalFormat("#.0") → "26.0"
        send(d, key: d.keys.temp, value: text, ki: ACKeyIndex.temp)
    }

    /// Mirrors `ACDevice.setACMode`: on "Papagz" models (102010/102011) JetStream and EcoNavi are
    /// switched off first; any mode change ends a non-default sleep profile.
    func setMode(_ d: ACDevice, _ mode: String) {
        if d.isPapagz {
            for name in ["JetStream", "EcoNavi"] {
                if let cap = d.capability(name), d.isOn(cap), let c = current(d) { send(c, capability: cap, value: "off") }
            }
        }
        update(d) { $0.mode = mode }
        send(d, key: d.keys.mode, value: mode, ki: ACKeyIndex.mode)
        resetSleepProfileIfNeeded(d)
    }

    /// Toggle with the official app's side effects (`ACDevice.setEcoMode`, `setACPowerfulMode`,
    /// `setACJetStreamMode`, `setTurboMode`, `setEcoNaviVal`).
    func setToggle(_ d: ACDevice, _ cap: ACCapability, _ on: Bool) {
        func conv0() {
            if let conv = d.capability("Convertible"), let c = current(d) { send(c, capability: conv, value: 0) }
        }
        func swing(_ name: String, _ v: Int) {
            if let s = d.capability(name), let c = current(d) { send(c, capability: s, value: v) }
        }
        switch (cap.name, on) {
        case ("ECOMode", true):
            send(d, capability: cap, value: "on")
            if let c = current(d) { setTemp(c, 26) }
            resetSleepProfileIfNeeded(d)
        case ("PowerfulMode", true):
            resetSleepProfileIfNeeded(d)
            conv0()
            if let c = current(d) { send(c, capability: cap, value: "on") }
        case ("JetStream", true):
            conv0()
            if let c = current(d) { send(c, capability: cap, value: "on") }
            swing("VerticalSwing", 1)
            swing("HorizontalSwing", 1)
            resetSleepProfileIfNeeded(d)
            if d.isPapagz, let navi = d.capability("EcoNavi"), d.isOn(navi), let c = current(d) {
                send(c, capability: navi, value: "off")
            }
        case ("TurboMode", true):
            send(d, capability: cap, value: "on")
            swing("VerticalSwing", 1)
        case ("EcoNavi", true):
            send(d, capability: cap, value: "on")
            if d.isPapagz, let jet = d.capability("JetStream"), d.isOn(jet), let c = current(d) {
                send(c, capability: jet, value: "off")
            }
        default:
            send(d, capability: cap, value: on ? "on" : "off")
        }
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

    // MARK: Timer (ACTimer, PROTOCOL.md §1.8)

    enum TimerSlot: Int { case off = 0, on = 1 }

    /// Active timer fire times from status `actm` (UTC epoch seconds, -1 = unset); nil when unset or past.
    func timerDate(_ d: ACDevice, _ slot: TimerSlot) -> Date? {
        guard let cap = d.capability("ACTimer"),
              let arr = d.state.raw[cap.key]?.arrayValue, arr.count == 2,
              let epoch = arr[slot.rawValue].doubleValue, epoch > 0 else { return nil }
        let date = Date(timeIntervalSince1970: epoch)
        return date > Date() ? date : nil
    }

    /// Sets (seconds from now) or cancels (nil) one timer slot. Sends `[offSeconds, onSeconds]`
    /// as relative seconds, keeping the other slot as its remaining time (-1 when unset).
    func setTimer(_ d: ACDevice, _ slot: TimerSlot, seconds: Int?) {
        guard let cap = d.capability("ACTimer"), let i = devices.firstIndex(where: { $0.id == d.id }) else { return }
        let now = Date()
        func remaining(_ s: TimerSlot) -> Int {
            guard let date = timerDate(devices[i], s) else { return -1 }
            return max(1, Int(date.timeIntervalSince(now)))
        }
        var relative = [remaining(.off), remaining(.on)]
        relative[slot.rawValue] = seconds.map { max(1, $0) } ?? -1
        // Optimistic status in the AC's own format (UTC epoch seconds).
        let utc = relative.map { $0 < 0 ? -1.0 : (now.timeIntervalSince1970 + Double($0)).rounded() }
        devices[i].state.raw[cap.key] = .array(utc.map(JSONValue.number))
        send(devices[i], key: cap.key, value: relative, ki: cap.ki)
    }

    // MARK: Local profiles

    private static func profilesKey(_ id: String) -> String { "acProfiles-\(id)" }

    private static func loadProfiles(_ id: String) -> [ACProfile] {
        guard let data = UserDefaults.standard.data(forKey: profilesKey(id)) else { return [] }
        return (try? JSONDecoder().decode([ACProfile].self, from: data)) ?? []
    }

    private func storeProfiles(_ id: String) {
        if let data = try? JSONEncoder().encode(profiles[id] ?? []) {
            UserDefaults.standard.set(data, forKey: Self.profilesKey(id))
        }
    }

    /// Saves the AC's current settings under `name` (replacing a profile with the same name).
    func saveProfile(_ d: ACDevice, name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let raw = d.state.raw
        let p = ACProfile(
            name: trimmed, power: d.state.power, mode: d.state.mode, temp: d.state.setTemp, fan: d.state.fan,
            verticalSwing: d.capability("VerticalSwing").flatMap { raw[$0.key]?.intValue },
            horizontalSwing: d.capability("HorizontalSwing").flatMap { raw[$0.key]?.intValue },
            display: d.capability("DisplayControl").flatMap { raw[$0.key]?.stringValue }
        )
        var list = profiles[d.id] ?? []
        list.removeAll { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }
        list.append(p)
        profiles[d.id] = list
        storeProfiles(d.id)
    }

    func deleteProfile(_ d: ACDevice, _ p: ACProfile) {
        profiles[d.id]?.removeAll { $0.id == p.id }
        storeProfiles(d.id)
    }

    /// Applies a profile as separate commands ~300 ms apart: power first when turning on,
    /// only settings that differ, and power last when the profile turns the AC off.
    func applyProfile(_ d: ACDevice, _ p: ACProfile) async {
        var steps: [() -> Void] = []
        func current() -> ACDevice? { devices.first { $0.id == d.id } }
        guard let start = current() else { return }
        if !p.power {
            if start.state.power { steps.append { [weak self] in if let c = current() { self?.setPower(c, false) } } }
        } else {
            if !start.state.power { steps.append { [weak self] in if let c = current() { self?.setPower(c, true) } } }
            if start.state.mode != p.mode { steps.append { [weak self] in if let c = current() { self?.setMode(c, p.mode) } } }
            if start.state.setTemp != p.temp { steps.append { [weak self] in if let c = current() { self?.setTemp(c, p.temp) } } }
            if start.state.fan != p.fan { steps.append { [weak self] in if let c = current() { self?.setFan(c, p.fan) } } }
            for (name, value) in [("VerticalSwing", p.verticalSwing), ("HorizontalSwing", p.horizontalSwing)] {
                if let value, let cap = start.capability(name), start.state.raw[cap.key]?.intValue != value {
                    steps.append { [weak self] in if let c = current() { self?.send(c, capability: cap, value: value) } }
                }
            }
            if let display = p.display, let cap = start.capability("DisplayControl"),
               start.state.raw[cap.key]?.stringValue != display {
                steps.append { [weak self] in if let c = current() { self?.send(c, capability: cap, value: display) } }
            }
        }
        for (n, step) in steps.enumerated() {
            if n > 0 { try? await Task.sleep(for: .milliseconds(300)) }
            step()
        }
    }
}

/// A saved set of AC settings, applied locally as individual commands.
struct ACProfile: Codable, Identifiable, Equatable {
    var id = UUID()
    var name: String
    var power: Bool
    var mode: String
    var temp: Double
    var fan: String
    var verticalSwing: Int?
    var horizontalSwing: Int?
    var display: String?
}

#if DEBUG
extension ACStore {
    /// Sample data for UI snapshots (`HOMEBAR_SNAPSHOT`). The first AC mirrors the real model 130185
    /// and status from the user's diagnostics (PROTOCOL.md §1.8), with an active off timer.
    func seedDemo() {
        loggedIn = true
        cloudSignedIn = true
        homeSpaces = ["h": [ACSpace(id: "s1", name: "Bedroom"), ACSpace(id: "s2", name: "Living Room"), ACSpace(id: "s3", name: "Study")]]
        var bedroom = ACDevice(id: "d1", name: "Bedroom AC", space: "Bedroom", homeId: "h", spaceId: "s1", topic: "h/d1", modelId: "130185")
        bedroom.state = ACState(power: true, mode: "cool", setTemp: 26, roomTemp: 32, fan: "auto", online: true)
        bedroom.transport = .cloud
        bedroom.udpCapable = false
        bedroom.model = try? JSONDecoder().decode(JSONValue.self, from: Data(Self.demoModel.utf8))
        let offAt = (Date().timeIntervalSince1970 + 2 * 3600).rounded()
        bedroom.state.raw = [
            "V": .string("3.02"), "ps": .string("on"), "acmd": .string("cool"), "actmp": .string("26.0"),
            "rmtmp": .string("32.0"), "acfs": .string("auto"), "acvs": .number(0), "achs": .number(0),
            "acdc": .string("on"), "acem": .string("off"), "acec": .string("off"), "acpm": .string("off"),
            "bzr": .string("on"), "cnv": .number(0), "acsp": .string("none"),
            "actm": .array([.number(offAt), .number(-1)]),
            "errors": .string(""), "warnings": .string(""), "filterDustLevel": .number(0),
            "filterCleaningRequired": .bool(true), "filterCleaningCause": .string("Operating hours"),
            "rssi": .number(-56), "onlineStatus": .string("true"), "totalOperatingHours": .number(212),
        ]
        var living = ACDevice(id: "d2", name: "Living Room AC", space: "Living Room", homeId: "h", topic: "h/d2", modelId: nil)
        living.state = ACState(power: false, mode: "dry", setTemp: 25.5, roomTemp: 30, fan: "low", online: true)
        living.transport = .cloud
        var study = ACDevice(id: "d3", name: "Study AC", space: "Study", homeId: "h", spaceId: "s3", topic: "h/d3", modelId: "demo-rich")
        study.state = ACState(power: true, mode: "cool", setTemp: 24, roomTemp: 29, fan: "auto", online: true)
        study.transport = .local
        study.model = try? JSONDecoder().decode(JSONValue.self, from: Data(Self.demoRichModel.utf8))
        study.state.raw = [
            "ps": .string("on"), "acmd": .string("cool"), "actmp": .string("24.0"), "acfs": .string("auto"),
            "acvs": .number(0), "achs": .number(0), "cnv": .number(80), "acjs": .string("off"), "acen": .string("off"),
            "acnga": .string("auto"), "achm": .string("off"), "acld": .string("off"), "acpm25": .string("74"),
            "acsp": .string("p1"), "filterDustLevel": .number(35), "filterCleaningRequired": .bool(false),
            "rssi": .number(-63), "V": .string("4.10"),
        ]
        devices = [bedroom, living, study]
        let night = SleepProfile(id: "p1", name: "Cool night", temps: [24, 25, 26, 27, 27, 28], offAtEnd: true, ruleIds: ["r1"])
        sleepProfiles = [
            "d1": [SleepProfile(id: "p0", name: "default", temps: [26, 27, 27, 28, 28, 28, 28, 28], offAtEnd: false), night],
            "d3": [night],
        ]
        sleepSchedules = ["r1": SleepSchedule(ruleId: "r1", hour: 22, minute: 30, weekdays: Set(2...6), enabled: true)]
        unreadAlerts = 1
        profiles["d1"] = [
            ACProfile(name: "Night", power: true, mode: "cool", temp: 27, fan: "quiet", verticalSwing: 0, horizontalSwing: 0, display: "off"),
            ACProfile(name: "Quick cool", power: true, mode: "cool", temp: 22, fan: "high", verticalSwing: 0, horizontalSwing: 0, display: "on"),
        ]
    }

    /// A feature-rich sample model (not a real one) to render every model-driven control.
    static let demoRichModel = """
    {"configCapabilities":[{"capability":"udpControl"}],
     "controlCapabilities":[
      {"capability":"ACMode","attributes":[{"key":"acmd","values":["auto","cool","dry","fan","heat"]}]},
      {"capability":"ACFanSpeed","attributes":[{"key":"acfs","values":["auto","quiet","low","medium","high"]}]},
      {"capability":"PowerStatus","attributes":[{"key":"ps"}]},
      {"capability":"JetStream","attributes":[{"key":"acjs"}]},
      {"capability":"EcoNavi","attributes":[{"key":"acen"}]},
      {"capability":"NanoeGA","attributes":[{"key":"acnga"}]},
      {"capability":"Humidifier","attributes":[{"key":"achm"}]},
      {"capability":"LaundryMode","attributes":[{"key":"acld"}]},
      {"capability":"Convertible","attributes":[{"key":"cnv","intValues":[110,100,90,80,70,55,40,0]}]},
      {"capability":"VerticalSwing","attributes":[{"key":"acvs","range":{"increment":[{"value":1}],"max":[{"value":5}],"min":[{"value":0}]}}]},
      {"capability":"HorizontalSwing","attributes":[{"key":"achs","range":{"increment":[{"value":1}],"max":[{"value":5}],"min":[{"value":0}]}}]},
      {"capability":"SleepProfile","attributes":[{"key":"acsp"}]},
      {"capability":"FilterDustLevel","attributes":[{"key":"filterDustLevel"},{"key":"acfcal"}]},
      {"capability":"FilterCalibration","attributes":[{"key":"acfcal"}]},
      {"capability":"PM25Sensor","attributes":[{"key":"acpm25"}]}
     ],
     "statusCapabilitiesV2":[{"capability":"PowerConsumption"}]}
    """

    /// Model 130185 exactly as returned by `deviceManagement/deviceModels/modelId/130185`.
    static let demoModel = """
    {"configCapabilities":[{"capability":"ProximityNotification"}],
     "controlCapabilities":[
      {"capability":"ACFanSpeed","attributes":[{"key":"acfs","values":["auto","quiet","low","medium","high"]}]},
      {"capability":"ACMode","attributes":[{"key":"acmd","values":["auto","cool","dry","fan"]}]},
      {"capability":"ACTemperature","attributes":[{"key":"actmp","range":{
        "increment":[{"unit":"celcius","value":1},{"unit":"fahrenheit","value":1}],
        "max":[{"unit":"celcius","value":30},{"unit":"fahrenheit","value":86}],
        "min":[{"unit":"celcius","value":16},{"unit":"fahrenheit","value":61}]}}]},
      {"capability":"ACTimer","attributes":[{"key":"actm","range":{"increment":[{"value":10}],"max":[{"value":1440}],"min":[{"value":10}]}}]},
      {"capability":"Convertible","attributes":[{"key":"cnv","intValues":[110,100,90,80,70,55,40,0]}]},
      {"capability":"DisplayControl","attributes":[{"key":"acdc","values":["on","off"]}]},
      {"capability":"ECOMode","attributes":[{"key":"acem","values":["on","off"]}]},
      {"capability":"EvaporatorClean","attributes":[{"key":"acec"}]},
      {"capability":"HorizontalSwing","attributes":[{"key":"achs","range":{"increment":[{"value":1}],"max":[{"value":5}],"min":[{"value":0}]}}]},
      {"capability":"PowerStatus","attributes":[{"key":"ps"}]},
      {"capability":"PowerfulMode","attributes":[{"key":"acpm"}]},
      {"capability":"SleepProfile","attributes":[{"key":"acsp"},
        {"key":"duration","range":{"increment":[{"unit":"hours","value":1}],"max":[{"unit":"hours","value":8}],"min":[{"unit":"hours","value":2}]}},
        {"key":"temps","range":{"increment":[{"unit":"celcius","value":1},{"unit":"fahrenheit","value":1}],
          "max":[{"unit":"celcius","value":30},{"unit":"fahrenheit","value":86}],"min":[{"unit":"celcius","value":16},{"unit":"fahrenheit","value":61}]}}]},
      {"capability":"TimeBasedFilterHealth","attributes":[{"key":"acfh","range":{"alert":[{"unit":"hours","value":500}],
        "reminder":[{"unit":"hours","value":100}],"warning":[{"unit":"hours","value":200}]}}]},
      {"capability":"VerticalSwing","attributes":[{"key":"acvs","range":{"increment":[{"value":1}],"max":[{"value":5}],"min":[{"value":0}]}}]},
      {"capability":"BuzzerControl","attributes":[{"key":"bzr","minFwVersion":3.02,"values":["on","off"]}]}
     ],
     "statusCapabilitiesV2":[{"capability":"ACFaults"},{"capability":"PowerConsumption"}]}
    """
}
#endif
