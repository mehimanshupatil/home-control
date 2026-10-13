import Foundation

// Cloud-backed AC features (PROTOCOL.md §1.9–1.14). All need a MirAIe session; see `cloudBlocker`.

struct ACSpace: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
}

// MARK: Energy (§1.9)

enum EnergyGrain: String, CaseIterable, Identifiable {
    case daily = "Daily", weekly = "Weekly", monthly = "Monthly"
    var id: String { rawValue }
    var title: String { self == .daily ? "Day" : self == .weekly ? "Week" : "Month" }
}

struct EnergyPoint: Identifiable, Equatable {
    let key: String     // ddMMyyyy (day / week start) or MMyyyy (month), as the API labels buckets
    let date: Date
    var kWh: Double
    var id: String { key }
}

/// Date window and request parameters, mirroring `DayHelper` / `EnergyConsumption`:
/// Daily = today-6 … today; Weekly = 7 weeks (start, end = start of the last week);
/// Monthly = this month and the 6 before.
struct EnergyWindow: Equatable {
    let grain: EnergyGrain
    let buckets: [Date]
    let startDate: String
    let endDate: String

    static func make(_ grain: EnergyGrain, now: Date = Date(), calendar: Calendar = .current) -> EnergyWindow {
        let today = calendar.startOfDay(for: now)
        func add(_ c: Calendar.Component, _ n: Int, _ d: Date) -> Date { calendar.date(byAdding: c, value: n, to: d)! }
        switch grain {
        case .daily:
            let start = add(.day, -6, today)
            let buckets = (0..<7).map { add(.day, $0, start) }
            return EnergyWindow(grain: grain, buckets: buckets, startDate: format(start, "ddMMyyyy"), endDate: format(today, "ddMMyyyy"))
        case .weekly:
            let end = today
            let start = add(.day, -48, end)
            let buckets = (0..<7).map { add(.day, $0 * 7, start) }
            return EnergyWindow(grain: grain, buckets: buckets, startDate: format(start, "ddMMyyyy"),
                                endDate: format(add(.day, -6, end), "ddMMyyyy"))
        case .monthly:
            let thisMonth = calendar.date(from: calendar.dateComponents([.year, .month], from: today))!
            let start = add(.month, -6, thisMonth)
            let buckets = (0..<7).map { add(.month, $0, start) }
            return EnergyWindow(grain: grain, buckets: buckets, startDate: format(start, "MMyyyy"), endDate: format(thisMonth, "MMyyyy"))
        }
    }

    var keyFormat: String { grain == .monthly ? "MMyyyy" : "ddMMyyyy" }
    var responseField: String { grain == .daily ? "day" : grain == .weekly ? "week" : "month" }

    /// Buckets with zero for missing labels, matching the official chart.
    func points(from response: [[String: Any]]) -> [EnergyPoint] {
        var values: [String: Double] = [:]
        for item in response {
            guard let label = item[responseField] as? String else { continue }
            let raw = item["power"]
            values[label.lowercased()] = (raw as? Double) ?? Double((raw as? String) ?? "") ?? 0
        }
        return buckets.map { d in
            let key = Self.format(d, keyFormat)
            return EnergyPoint(key: key, date: d, kWh: values[key.lowercased()] ?? 0)
        }
    }

    static func format(_ d: Date, _ pattern: String) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = pattern
        return f.string(from: d)
    }
}

// MARK: Sleep profiles (§1.10)

/// One cloud sleep profile: hourly target temperatures, optionally switching the AC off at the end.
struct SleepProfile: Identifiable, Equatable, Codable {
    var id: String          // profileId ("" before creation)
    var name: String
    var temps: [Double]     // one per hour
    var offAtEnd: Bool
    var ruleIds: [String] = []

    var isDefault: Bool { name.lowercased() == "default" }
    var displayName: String { isDefault ? "Regular" : name }

    /// `SleepProfileEntity`: `%04.1f` per hour, plus "00.0" when the AC turns off at the end.
    var temperatureStrings: [String] {
        var out = temps.map { String(format: "%04.1f", $0) }
        if offAtEnd, temps.last != 0 { out.append("00.0") }
        return out
    }

    /// Parses the API entity (`ACProfile(SleepProfileEntity)`): a trailing 0 means "off at end".
    init?(entity e: [String: Any]) {
        guard let id = e["profileId"] as? String else { return nil }
        self.id = id
        name = e["profileName"] as? String ?? "Profile"
        ruleIds = e["smartRuleId"] as? [String] ?? []
        let values = (e["temperatures"] as? [Any] ?? []).compactMap { v -> Double? in
            (v as? Double) ?? Double((v as? String) ?? "")
        }
        if let last = values.last, last == 0 {
            offAtEnd = true
            temps = Array(values.dropLast())
        } else {
            offAtEnd = false
            temps = values
        }
    }

    init(id: String = "", name: String, temps: [Double], offAtEnd: Bool, ruleIds: [String] = []) {
        self.id = id
        self.name = name
        self.temps = temps
        self.offAtEnd = offAtEnd
        self.ruleIds = ruleIds
    }

    /// POST/PUT body (`SleepProfileEntity` / `SleepProfileRequestEntity`).
    var requestBody: [String: Any] { ["profileName": name, "temperatures": temperatureStrings] }

    /// Device command (`ACProfileCapability.buildMessage`): `{acsp: on|off, ki, pid, temps}`;
    /// the default profile uses pid "default".
    func activationFields(key: String, on: Bool) -> [String: Any] {
        [key: on ? "on" : "off", "pid": isDefault ? "default" : id, "temps": temperatureStrings]
    }

    /// Rule action payload (`buildPayloadForRule`): a JSON string without temps.
    func rulePayloadString(key: String, ki: Int) -> String {
        let obj: [String: Any] = [key: "on", "ki": ki, "pid": isDefault ? "default" : id]
        let data = (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }
}

/// A weekly schedule that activates a sleep profile (`rms` rule, Timer trigger).
struct SleepSchedule: Equatable, Codable {
    var ruleId: String
    var hour: Int
    var minute: Int
    /// Calendar weekdays 1 = Sunday … 7 = Saturday; empty = once.
    var weekdays: Set<Int>
    var enabled: Bool

    static let dayCodes = [1: "S", 2: "M", 3: "T", 4: "W", 5: "TH", 6: "F", 7: "SA"]

    /// `RuleItem.TimerTrigger.getTriggerTypeString`: "daily", "once" or Sunday-first codes.
    var repeatType: String {
        if weekdays.count == 7 { return "daily" }
        if weekdays.isEmpty { return "once" }
        return weekdays.sorted().compactMap { Self.dayCodes[$0] }.joined(separator: ",")
    }

    static func weekdays(from repeatType: String) -> Set<Int> {
        if repeatType == "daily" { return Set(1...7) }
        let inverse = Dictionary(uniqueKeysWithValues: dayCodes.map { ($0.value, $0.key) })
        return Set(repeatType.split(separator: ",").compactMap { inverse[String($0)] })
    }

    var summary: String {
        let time = String(format: "%02d:%02d", hour, minute)
        if weekdays.count == 7 { return "Daily at \(time)" }
        if weekdays == Set(2...6) { return "Weekdays at \(time)" }
        if weekdays == [1, 7] { return "Weekends at \(time)" }
        if weekdays.isEmpty { return "Once at \(time)" }
        let names = weekdays.sorted().map { Calendar.current.shortWeekdaySymbols[$0 - 1] }
        return "\(names.joined(separator: " ")) at \(time)"
    }

    /// `RuleItem.getRulePayload` for a sleep-profile action.
    static func rulePayload(name: String, homeId: String, userId: String, deviceId: String, profile: SleepProfile,
                            payload: String, hour: Int, minute: Int, weekdays: Set<Int>, ruleId: String? = nil) -> [String: Any] {
        let s = SleepSchedule(ruleId: ruleId ?? "", hour: hour, minute: minute, weekdays: weekdays, enabled: true)
        var body: [String: Any] = [
            "ruleName": name,
            "homeId": homeId,
            "userId": userId,
            "primaryUserId": userId,
            "ruleType": "User",
            "schemaVersion": "v2",
            "ruleStatus": "ENABLE",
            "triggers": [[[
                "type": "Timer", "check": "EQ", "property": "Time",
                "value": hour * 100 + minute, "repeatType": s.repeatType,
            ]]],
            "rule": [
                "sendPushNotification": true,
                "actions": [[
                    "deviceId": deviceId, "profileId": profile.id,
                    "payload": payload, "payloadVersion": "0.1",
                ]],
            ],
        ]
        if let ruleId { body["ruleId"] = ruleId }
        return body
    }

    /// Parses `GET rms/homes/{h}/rules/{ruleId}` (v2 `triggers` or legacy `trigger`).
    init?(ruleId: String, rule r: [String: Any]) {
        let trigger = ((r["triggers"] as? [[[String: Any]]])?.first?.first) ?? (r["trigger"] as? [String: Any])
        guard let trigger, let value = (trigger["value"] as? Int) ?? Int("\(trigger["value"] ?? "")") else { return nil }
        self.ruleId = ruleId
        hour = value / 100
        minute = value % 100
        weekdays = Self.weekdays(from: trigger["repeatType"] as? String ?? "once")
        enabled = (r["ruleStatus"] as? String ?? "ENABLE") == "ENABLE"
    }

    init(ruleId: String, hour: Int, minute: Int, weekdays: Set<Int>, enabled: Bool) {
        self.ruleId = ruleId
        self.hour = hour
        self.minute = minute
        self.weekdays = weekdays
        self.enabled = enabled
    }
}

// MARK: Firmware, notifications

struct FirmwareInfo: Equatable {
    var current: String?
    var latest: String?
    var isLatest: Bool
}

struct ACNotification: Identifiable, Equatable {
    let id: String
    let severity: String
    let date: Date?
    let title: String
    let message: String
    let deviceName: String?

    /// `NWResponseItem`: `ts` (seconds), `message.payload` with `title`, `message`/`text`, `deviceName`.
    init?(_ item: [String: Any]) {
        let payload = (item["message"] as? [String: Any])?["payload"] as? [String: Any] ?? [:]
        id = item["notificationId"] as? String ?? item["uniqueId"] as? String ?? UUID().uuidString
        severity = item["severity"] as? String ?? "info"
        let ts = (item["ts"] as? Double) ?? Double("\(item["ts"] ?? "")")
        date = ts.map { Date(timeIntervalSince1970: $0 > 1e12 ? $0 / 1000 : $0) }
        title = payload["title"] as? String ?? (payload["category"] as? String ?? "Notification")
        message = payload["message"] as? String ?? payload["text"] as? String ?? ""
        deviceName = payload["deviceName"] as? String
    }

    init(id: String, severity: String, date: Date?, title: String, message: String, deviceName: String?) {
        self.id = id
        self.severity = severity
        self.date = date
        self.title = title
        self.message = message
        self.deviceName = deviceName
    }
}

// MARK: Store

extension ACDevice {
    /// "Papagz" models get extra JetStream/EcoNavi exclusivity in the official app.
    var isPapagz: Bool { modelId == "102010" || modelId == "102011" }

    func isOn(_ cap: ACCapability) -> Bool { state.raw[cap.key]?.stringValue?.lowercased() == "on" }

    /// Active sleep profile id from status `acsp` (nil when "none"/empty).
    var activeSleepProfileId: String? {
        guard let cap = capability("SleepProfile"), let v = state.raw[cap.key]?.stringValue,
              !v.isEmpty, v.lowercased() != "none", v.lowercased() != "off" else { return nil }
        return v
    }

    /// SleepProfile `duration` (hours) and `temps` ranges.
    var sleepDurationRange: ClosedRange<Int> {
        let r = ACCapability.attributeRange("SleepProfile", key: "duration", in: model)
        return Int(r?.min ?? 2)...Int(r?.max ?? 8)
    }

    var sleepTempRange: ACRange {
        ACCapability.attributeRange("SleepProfile", key: "temps", in: model) ?? tempRange
    }

    /// Filter thresholds (hours) from TimeBasedFilterHealth `range.reminder/warning/alert`.
    var filterThresholds: (reminder: Double, warning: Double, alert: Double)? {
        guard let attr = ACCapability.attribute("TimeBasedFilterHealth", in: model), let r = attr["range"] else { return nil }
        func v(_ f: String) -> Double? { r[f]?.arrayValue.first?["value"]?.doubleValue }
        guard let a = v("alert") else { return nil }
        return (v("reminder") ?? a / 5, v("warning") ?? a * 2 / 5, a)
    }
}

extension ACCapability {
    /// Range of a named attribute (not just the first) inside a capability.
    static func attributeRange(_ name: String, key: String, in model: JSONValue?) -> ACRange? {
        for field in ["controlCapabilities", "statusCapabilitiesV2"] {
            for cap in model?[field]?.arrayValue ?? [] where cap["capability"]?.stringValue == name {
                for attr in cap["attributes"]?.arrayValue ?? [] where attr["key"]?.stringValue == key {
                    guard let r = attr["range"] else { return nil }
                    func pick(_ f: String) -> Double? {
                        let entries = r[f]?.arrayValue ?? []
                        let c = entries.first { ["celcius", "celsius"].contains($0["unit"]?.stringValue?.lowercased() ?? "") }
                        return (c ?? entries.first)?["value"]?.doubleValue
                    }
                    guard let lo = pick("min"), let hi = pick("max") else { return nil }
                    return ACRange(min: lo, max: hi, step: pick("increment") ?? 1)
                }
            }
        }
        return nil
    }
}

extension ACStore {
    #if DEBUG
    nonisolated static var isHarness: Bool {
        let env = ProcessInfo.processInfo.environment
        return env["HOMEBAR_SNAPSHOT"] != nil || env["HOMEBAR_KEYTEST"] != nil
    }
    #else
    nonisolated static var isHarness: Bool { false }
    #endif

    /// Why a cloud-only feature can't run right now, or nil when it can.
    var cloudBlocker: String? {
        if !cloudSignedIn { return "Needs MirAIe sign-in" }
        if localOnly { return "Turn off Local Network Only to use this" }
        return nil
    }

    /// After a cloud reload: sleep profiles + schedules for each AC, and the unread alert count.
    func loadCloudExtras() async {
        guard cloudBlocker == nil, !Self.isHarness else { return }
        for d in devices where d.capability("SleepProfile") != nil {
            await loadSleepProfiles(d)
        }
        await refreshUnreadCount()
    }

    // MARK: Energy

    func energy(_ d: ACDevice, _ grain: EnergyGrain) async throws -> [EnergyPoint] {
        let w = EnergyWindow.make(grain)
        #if DEBUG
        if Self.isHarness { return w.points(from: Self.demoEnergy(w)) }
        #endif
        let resp = try await api.request("GET", "powerConsumption/devices/\(d.id)",
                                         query: ["grain": grain.rawValue, "startDate": w.startDate, "endDate": w.endDate])
        return w.points(from: resp as? [[String: Any]] ?? [])
    }

    // MARK: Sleep profiles

    private func profilePath(_ d: ACDevice) -> String? {
        guard let space = d.spaceId else { return nil }
        return "homeManagement/homes/\(d.homeId)/spaces/\(space)/devices/\(d.id)/sleepProfile"
    }

    func loadSleepProfiles(_ d: ACDevice) async {
        guard let path = profilePath(d), !Self.isHarness else { return }
        do {
            let list = try await api.request("GET", path) as? [[String: Any]] ?? []
            var profiles = list.compactMap(SleepProfile.init(entity:))
            profiles.sort { ($0.isDefault ? 0 : 1, $0.name) < ($1.isDefault ? 0 : 1, $1.name) }
            sleepProfiles[d.id] = profiles
            for ruleId in Set(profiles.flatMap(\.ruleIds)) { await loadSchedule(homeId: d.homeId, ruleId: ruleId) }
        } catch {
            errorMessage = "Couldn't load sleep profiles: \(error.localizedDescription)"
        }
    }

    /// Creates (empty id) or updates a profile. At most 3 custom profiles plus "default".
    func saveSleepProfile(_ d: ACDevice, _ p: SleepProfile) async throws {
        guard let path = profilePath(d) else { throw MirAIeError.badResponse }
        if p.id.isEmpty {
            let custom = (sleepProfiles[d.id] ?? []).filter { !$0.isDefault }
            guard custom.count < 3 else { throw ACCloudError.limit("You can have up to 3 custom sleep profiles.") }
            try await api.request("POST", path, body: p.requestBody)
        } else {
            try await api.request("PUT", "\(path)/\(p.id)", body: p.requestBody)
        }
        await loadSleepProfiles(d)
    }

    /// Deletes the profile's schedule first, then the profile (as `SleepProfileActivity` does).
    func deleteSleepProfile(_ d: ACDevice, _ p: SleepProfile) async throws {
        guard let path = profilePath(d), !p.isDefault else { return }
        if let rule = p.ruleIds.first { try await api.request("DELETE", "rms/homes/\(d.homeId)/rules/\(rule)") }
        try await api.request("DELETE", "\(path)/\(p.id)")
        await loadSleepProfiles(d)
    }

    /// Activates or stops a profile (`ACDevice.updateProfileStatus`). Starting a custom profile
    /// while in Fan mode also switches the mode to Auto.
    func setSleepProfile(_ d: ACDevice, _ p: SleepProfile, on: Bool) {
        guard let cap = d.capability("SleepProfile"), let i = devices.firstIndex(where: { $0.id == d.id }) else { return }
        devices[i].state.raw[cap.key] = .string(on ? (p.isDefault ? "default" : p.id) : "none")
        sendFields(devices[i], p.activationFields(key: cap.key, on: on), ki: cap.ki)
        if on, !p.isDefault, d.state.mode == "fan", let c = current(d) {
            update(c) { $0.mode = "auto" }
            sendFields(c, [c.keys.mode: "auto"], ki: ACKeyIndex.mode)
        }
    }

    /// `ACDevice.resetSleepProfileIfNeeded`: stops a running non-default profile.
    func resetSleepProfileIfNeeded(_ d: ACDevice) {
        guard let c = current(d), let active = c.activeSleepProfileId, active != "default",
              let p = sleepProfiles[d.id]?.first(where: { $0.id == active }), !p.isDefault else { return }
        setSleepProfile(c, p, on: false)
    }

    // MARK: Sleep schedules (rms rules)

    func loadSchedule(homeId: String, ruleId: String) async {
        guard let r = try? await api.request("GET", "rms/homes/\(homeId)/rules/\(ruleId)") as? [String: Any],
              let s = SleepSchedule(ruleId: ruleId, rule: r) else { return }
        sleepSchedules[ruleId] = s
    }

    /// Creates or updates the profile's schedule (one rule per profile, like the official app).
    func scheduleSleepProfile(_ d: ACDevice, _ p: SleepProfile, hour: Int, minute: Int, weekdays: Set<Int>) async throws {
        guard let cap = d.capability("SleepProfile"), let userId = await api.userId else { throw MirAIeError.notLoggedIn }
        let existing = p.ruleIds.first
        let body = SleepSchedule.rulePayload(
            name: p.isDefault ? "Regular" : "Schedule \(p.name)", homeId: d.homeId, userId: userId, deviceId: d.id,
            profile: p, payload: p.rulePayloadString(key: cap.key, ki: cap.ki),
            hour: hour, minute: minute, weekdays: weekdays, ruleId: existing)
        if let existing {
            try await api.request("PUT", "rms/homes/\(d.homeId)/rules/\(existing)", body: body)
        } else {
            let resp = try await api.request("POST", "rms/homes/\(d.homeId)/rules", body: body) as? [String: Any]
            // The server links the rule to the profile (`smartRuleId`); mirror it locally until the reload.
            if let ruleId = resp?["ruleId"] as? String,
               let j = sleepProfiles[d.id]?.firstIndex(where: { $0.id == p.id }) {
                sleepProfiles[d.id]?[j].ruleIds = [ruleId]
            }
        }
        await loadSleepProfiles(d)
    }

    func deleteSchedule(_ d: ACDevice, ruleId: String) async throws {
        try await api.request("DELETE", "rms/homes/\(d.homeId)/rules/\(ruleId)")
        sleepSchedules[ruleId] = nil
        await loadSleepProfiles(d)
    }

    /// Pause (no end) / resume, as `RuleItem` disable/enable.
    func setScheduleEnabled(_ d: ACDevice, ruleId: String, _ enabled: Bool) async throws {
        if enabled {
            try await api.request("PUT", "rms/homes/\(d.homeId)/rules/resume", body: [ruleId])
        } else {
            let now = String(Int(Date().timeIntervalSince1970 * 1000))
            try await api.request("PUT", "rms/homes/\(d.homeId)/rules/pause",
                                  body: [["ruleId": ruleId, "action": "pause", "startTime": now]])
        }
        sleepSchedules[ruleId]?.enabled = enabled
    }

    // MARK: Filter

    /// Time-based models: `PUT filterDustLevelInfo` then refresh status. Dust-based: FilterCalibration
    /// command, then wait up to 1 min for its value to become "complete".
    func resetFilter(_ d: ACDevice) async throws {
        if d.capability("TimeBasedFilterHealth") != nil {
            try await api.request("PUT", "deviceManagement/devices/\(d.id)/filterDustLevelInfo")
            if let status = try await api.request("GET", "deviceManagement/devices/\(d.id)/mobile/status") as? [String: Any],
               let i = devices.firstIndex(where: { $0.id == d.id }) {
                for (k, v) in status { devices[i].state.raw[k] = JSONValue(any: v) }
            }
        } else if let cal = d.capability("FilterCalibration") {
            sendFields(d, [cal.key: "on"], ki: cal.ki)
            for _ in 0..<30 {
                try await Task.sleep(for: .seconds(2))
                if current(d)?.state.raw[cal.key]?.stringValue?.lowercased() == "complete" { return }
            }
            throw ACCloudError.limit("The AC didn't confirm the filter reset within a minute.")
        }
    }

    // MARK: Rename / move

    func rename(_ d: ACDevice, to name: String) async throws {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let space = d.spaceId else { return }
        try await api.request("PUT", "homeManagement/homes/\(d.homeId)/spaces/\(space)/devices/\(d.id)",
                              body: ["deviceName": trimmed])
        mutate(d) { $0.name = trimmed }
    }

    func move(_ d: ACDevice, to space: ACSpace) async throws {
        guard let from = d.spaceId, from != space.id else { return }
        try await api.request("POST", "homeManagement/homes/\(d.homeId)/spaces/\(from)/devices/\(d.id)",
                              body: ["newSpaceId": space.id])
        mutate(d) { $0.spaceId = space.id; $0.space = space.name }
    }

    private func mutate(_ d: ACDevice, _ change: (inout ACDevice) -> Void) {
        guard let i = devices.firstIndex(where: { $0.id == d.id }) else { return }
        change(&devices[i])
        if let data = try? JSONEncoder().encode(devices) { UserDefaults.standard.set(data, forKey: "acDevices") }
    }

    // MARK: Firmware (check only — never triggers an update)

    func checkFirmware(_ d: ACDevice) async throws -> FirmwareInfo {
        guard !Self.isHarness else { return FirmwareInfo(current: d.state.raw["V"]?.stringValue, latest: nil, isLatest: true) }
        let current = d.state.raw["V"]?.stringValue
        let resp = try await api.request("GET", "fota/firmware/deviceId/\(d.id)/download",
                                         query: ["currentVersion": current ?? ""]) as? [String: Any]
        let latest = resp?["firmwareVersion"] as? String
        let isLatest = (resp?["isLatestVersion"] as? Bool) ?? (latest == nil || latest == current)
        return FirmwareInfo(current: current, latest: latest, isLatest: isLatest)
    }

    // MARK: Notifications (read-only)

    /// One page (20) per severity, merged and sorted newest first. Pages start at 1.
    func loadNotifications(page: Int) async throws -> Int {
        #if DEBUG
        if Self.isHarness { notifications = Self.demoNotifications; return notifications.count }
        #endif
        var added: [ACNotification] = []
        for severity in ["alert", "info", "recommendation"] {
            let list = try await api.request("GET", "notificationmanager/log",
                                             query: ["severity": severity, "pgIndex": String(page), "pgSize": "20"]) as? [[String: Any]] ?? []
            added += list.compactMap(ACNotification.init)
        }
        let merged = (page == 1 ? [] : notifications) + added
        var seen = Set<String>()
        notifications = merged.filter { seen.insert($0.id).inserted }
            .sorted { ($0.date ?? .distantPast) > ($1.date ?? .distantPast) }
        return added.count
    }

    func refreshUnreadCount() async {
        guard !Self.isHarness else { return }
        guard let r = try? await api.request("GET", "notificationmanager/unexpired/count", query: ["severity": "alert"]) as? [String: Any] else { return }
        unreadAlerts = (r["unexpired"] as? Int) ?? Int("\(r["unexpired"] ?? 0)") ?? 0
    }
}

enum ACCloudError: LocalizedError {
    case limit(String)
    var errorDescription: String? {
        switch self { case .limit(let m): m }
    }
}

#if DEBUG
extension ACStore {
    static let demoNotifications: [ACNotification] = [
        ACNotification(id: "n1", severity: "alert", date: Date().addingTimeInterval(-3600), title: "Time to clean the filter",
                       message: "Your AC has run 210 hours since the last filter cleaning.", deviceName: "Bedroom AC"),
        ACNotification(id: "n2", severity: "info", date: Date().addingTimeInterval(-86400), title: "Sleep profile started",
                       message: "“Cool night” started on Bedroom AC.", deviceName: "Bedroom AC"),
        ACNotification(id: "n3", severity: "recommendation", date: Date().addingTimeInterval(-3 * 86400), title: "Save energy",
                       message: "Setting 26° instead of 22° can cut cooling energy by about 20%.", deviceName: nil),
    ]

    static func demoEnergy(_ w: EnergyWindow) -> [[String: Any]] {
        let base: [Double] = w.grain == .daily ? [3.2, 4.1, 0, 5.6, 6.3, 4.8, 2.9]
            : w.grain == .weekly ? [18, 24.5, 31, 27.2, 35.4, 29.1, 12.3] : [40, 62, 118, 140, 155, 96, 51]
        return zip(w.buckets, base).compactMap { d, v in
            v == 0 ? nil : [w.responseField: EnergyWindow.format(d, w.keyFormat), "power": String(format: "%.2f", v)]
        }
    }
}
#endif
