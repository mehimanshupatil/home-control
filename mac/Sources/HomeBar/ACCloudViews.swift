import Charts
import SwiftUI

// Panels opened from an AC card: Energy, Sleep, Filter, Settings. Cloud-backed ones show a cloud
// caption and degrade to `ACStore.cloudBlocker` text when sign-in is missing or Local-only is on.

enum ACPanel: String, CaseIterable, Identifiable {
    case energy, sleep, filter, settings
    var id: String { rawValue }
    var title: String { rawValue.capitalized }
    var symbol: String {
        switch self {
        case .energy: "bolt.fill"
        case .sleep: "moon.zzz.fill"
        case .filter: "line.3.horizontal.decrease"
        case .settings: "slider.horizontal.3"
        }
    }
}

extension ACDevice {
    /// Panels that apply to this model.
    var panels: [ACPanel] {
        var out: [ACPanel] = []
        if capability("PowerConsumption") != nil || capability("CurrentPowerConsumption") != nil || model == nil { out.append(.energy) }
        if capability("SleepProfile") != nil { out.append(.sleep) }
        if ["TimeBasedFilterHealth", "FilterDustLevel", "FilterCalibration"].contains(where: { capability($0) != nil }) {
            out.append(.filter)
        }
        out.append(.settings)
        return out
    }
}

/// Row of panel chips; the selected panel opens below inside the card.
struct ACPanelBar: View {
    let device: ACDevice
    @Binding var selection: ACPanel?

    var body: some View {
        HStack(spacing: 4) {
            ForEach(device.panels) { p in
                OptionTile(title: p.title, symbol: p.symbol, selected: selection == p, compact: true) {
                    selection = selection == p ? nil : p
                }
                .accessibilityLabel("\(p.title) panel")
            }
        }
    }
}

struct ACPanelView: View {
    let device: ACDevice
    let panel: ACPanel

    var body: some View {
        Group {
            switch panel {
            case .energy: ACEnergyPanel(device: device)
            case .sleep: ACSleepPanel(device: device)
            case .filter: ACFilterPanel(device: device)
            case .settings: ACSettingsPanel(device: device)
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous).fill(Color.primary.opacity(0.04)))
    }
}

/// "Uses MirAIe cloud" caption, or the blocker text in place of the panel body.
struct CloudGate<Content: View>: View {
    @EnvironmentObject private var ac: ACStore
    var title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(title).font(.caption.weight(.semibold))
                Spacer()
                Label("MirAIe cloud", systemImage: "cloud")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .help("This feature uses Panasonic's servers")
            }
            if let blocker = ac.cloudBlocker {
                Label(blocker, systemImage: "icloud.slash")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                content()
            }
        }
    }
}

// MARK: Energy

struct ACEnergyPanel: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice

    @ViewState private var grain: EnergyGrain = .daily
    @ViewState private var points: [EnergyPoint] = []
    @ViewState private var loading = false
    @ViewState private var error: String?
    @ViewState private var selected: String?

    var body: some View {
        CloudGate(title: "Energy use") {
            VStack(alignment: .leading, spacing: 8) {
                Picker("Range", selection: $grain) {
                    ForEach(EnergyGrain.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .controlSize(.small)

                if loading && points.isEmpty {
                    ProgressView().controlSize(.small).frame(maxWidth: .infinity, minHeight: 120)
                } else if let error {
                    Label(error, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.red)
                } else if points.allSatisfy({ $0.kWh == 0 }) {
                    Text("No energy data for this period yet.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, minHeight: 60)
                } else {
                    summary
                    chart
                }
            }
            .task(id: grain) { await load() }
        }
    }

    private var summary: some View {
        let total = points.reduce(0) { $0 + $1.kWh }
        let sel = points.first { $0.key == selected }
        return HStack(alignment: .firstTextBaseline) {
            Text(String(format: "%.1f kWh", sel?.kWh ?? total))
                .font(.system(.title3, design: .rounded).weight(.semibold))
                .monospacedDigit()
            Text(sel.map { label($0, long: true) } ?? "total")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
        }
    }

    private var chart: some View {
        Chart(points) { p in
            BarMark(x: .value("Period", p.key), y: .value("kWh", p.kWh))
                .foregroundStyle(p.key == selected ? Color.accentColor : Color.accentColor.opacity(selected == nil ? 0.8 : 0.35))
                .cornerRadius(3)
        }
        .chartXAxis {
            AxisMarks(values: points.map(\.key)) { v in
                AxisValueLabel {
                    if let key = v.as(String.self), let p = points.first(where: { $0.key == key }) {
                        Text(label(p, long: false)).font(.system(size: 9))
                    }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading) { _ in
                AxisGridLine()
                AxisValueLabel().font(.system(size: 9))
            }
        }
        .chartXSelection(value: $selected)
        .frame(height: 130)
        .accessibilityLabel("Energy use chart")
    }

    private func label(_ p: EnergyPoint, long: Bool) -> String {
        let f = DateFormatter()
        switch grain {
        case .daily: f.dateFormat = long ? "EEEE d MMM" : "EEE"
        case .weekly: f.dateFormat = long ? "'Week of' d MMM" : "d/M"
        case .monthly: f.dateFormat = long ? "MMMM yyyy" : "MMM"
        }
        return f.string(from: p.date)
    }

    private func load() async {
        loading = true
        error = nil
        selected = nil
        defer { loading = false }
        do {
            points = try await ac.energy(device, grain)
        } catch {
            self.error = "Couldn't load energy data: \(error.localizedDescription)"
            points = []
        }
    }
}

// MARK: Sleep profiles

struct ACSleepPanel: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice

    @ViewState private var editing: SleepProfile?
    @ViewState private var scheduling: SleepProfile?
    @ViewState private var busy = false
    @ViewState private var error: String?

    private var profiles: [SleepProfile] { ac.sleepProfiles[device.id] ?? [] }

    var body: some View {
        CloudGate(title: "Sleep profiles") {
            VStack(alignment: .leading, spacing: 8) {
                Text("Hour-by-hour temperatures for the night. Starting a profile runs it on the AC.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                if let error { Label(error, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.red) }

                if let p = editing {
                    SleepProfileEditor(device: device, profile: p) { saved in
                        Task { await run { try await ac.saveSleepProfile(device, saved) }; editing = nil }
                    } cancel: { editing = nil }
                } else if let p = scheduling {
                    SleepScheduleEditor(device: device, profile: p, existing: p.ruleIds.first.flatMap { ac.sleepSchedules[$0] }) { h, m, days in
                        Task { await run { try await ac.scheduleSleepProfile(device, p, hour: h, minute: m, weekdays: days) }; scheduling = nil }
                    } remove: { ruleId in
                        Task { await run { try await ac.deleteSchedule(device, ruleId: ruleId) }; scheduling = nil }
                    } cancel: { scheduling = nil }
                } else {
                    if profiles.isEmpty {
                        Text(busy ? "Loading…" : "No sleep profiles yet.").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(profiles) { p in row(p) }
                    if profiles.filter({ !$0.isDefault }).count < 3 {
                        Button {
                            let r = device.sleepTempRange
                            editing = SleepProfile(name: "", temps: Array(repeating: min(r.max, max(r.min, 26)), count: 6), offAtEnd: true)
                        } label: {
                            Label("New profile", systemImage: "plus").font(.caption.weight(.medium))
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
            .task { if profiles.isEmpty { busy = true; await ac.loadSleepProfiles(device); busy = false } }
        }
    }

    private func row(_ p: SleepProfile) -> some View {
        let active = device.activeSleepProfileId == (p.isDefault ? "default" : p.id)
        let schedule = p.ruleIds.first.flatMap { ac.sleepSchedules[$0] }
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(p.displayName).font(.callout.weight(.semibold))
                        if active { StatusPill(text: "Running", color: .indigo, symbol: "moon.fill") }
                    }
                    Text(Self.summary(p)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                    if let schedule {
                        Label(schedule.summary + (schedule.enabled ? "" : " (paused)"), systemImage: "calendar")
                            .font(.caption2)
                            .foregroundStyle(schedule.enabled ? Color.secondary : Color.orange)
                    }
                }
                Spacer()
                SleepGraph(temps: p.temps, range: device.sleepTempRange).frame(width: 64, height: 26)
            }
            HStack(spacing: 10) {
                Button(active ? "Stop" : "Start") { ac.setSleepProfile(device, p, on: !active) }
                    .font(.caption.weight(.semibold))
                Button("Edit") { editing = p }
                Button(schedule == nil ? "Schedule…" : "Change schedule…") { scheduling = p }
                if let schedule {
                    Button(schedule.enabled ? "Pause" : "Resume") {
                        Task { await run { try await ac.setScheduleEnabled(device, ruleId: schedule.ruleId, !schedule.enabled) } }
                    }
                }
                Spacer()
                if !p.isDefault {
                    Button(role: .destructive) {
                        Task { await run { try await ac.deleteSleepProfile(device, p) } }
                    } label: { Image(systemName: "trash") }
                    .help("Delete profile (and its schedule)")
                    .accessibilityLabel("Delete \(p.displayName)")
                }
            }
            .buttonStyle(.borderless)
            .font(.caption)
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous).fill(Color.primary.opacity(active ? 0.08 : 0.04)))
    }

    static func summary(_ p: SleepProfile) -> String {
        let t = p.temps.map { String(format: "%.0f", $0) }
        let range = t.isEmpty ? "" : (t.first == t.last ? "\(t[0])°" : "\(t.first!)→\(t.last!)°")
        return "\(p.temps.count) h · \(range)" + (p.offAtEnd ? " · off at end" : "")
    }

    private func run(_ work: () async throws -> Void) async {
        busy = true
        error = nil
        defer { busy = false }
        do { try await work() } catch { self.error = error.localizedDescription }
    }
}

/// Tiny line chart of hourly temperatures.
struct SleepGraph: View {
    let temps: [Double]
    let range: ACRange

    var body: some View {
        Chart(Array(temps.enumerated()), id: \.offset) { i, t in
            LineMark(x: .value("Hour", i), y: .value("°C", t))
                .interpolationMethod(.stepCenter)
                .foregroundStyle(Color.indigo)
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartYScale(domain: range.min...range.max)
        .accessibilityHidden(true)
    }
}

struct SleepProfileEditor: View {
    let device: ACDevice
    @ViewState var profile: SleepProfile
    let save: (SleepProfile) -> Void
    let cancel: () -> Void

    init(device: ACDevice, profile: SleepProfile, save: @escaping (SleepProfile) -> Void, cancel: @escaping () -> Void) {
        self.device = device
        _profile = ViewState(initialValue: profile)
        self.save = save
        self.cancel = cancel
    }

    var body: some View {
        let r = device.sleepTempRange
        let hours = device.sleepDurationRange
        VStack(alignment: .leading, spacing: 8) {
            if profile.isDefault {
                Text("Regular profile").font(.caption.weight(.semibold))
            } else {
                TextField("Profile name", text: $profile.name).textFieldStyle(.roundedBorder)
            }
            Stepper(value: Binding(get: { profile.temps.count }, set: resize), in: hours) {
                Text("\(profile.temps.count) hours").font(.caption).monospacedDigit()
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 2), spacing: 6) {
                ForEach(profile.temps.indices, id: \.self) { i in
                    HStack(spacing: 4) {
                        Text("Hour \(i + 1)").font(.caption2).foregroundStyle(.secondary)
                        Spacer(minLength: 2)
                        Stepper(value: $profile.temps[i], in: r.min...r.max, step: max(1, r.step)) {
                            Text(String(format: "%.0f°", profile.temps[i])).font(.caption).monospacedDigit()
                        }
                        .controlSize(.small)
                    }
                }
            }
            SleepGraph(temps: profile.temps, range: r).frame(height: 40)
            Toggle("Turn the AC off at the end", isOn: $profile.offAtEnd).font(.caption).toggleStyle(.checkbox)
            HStack {
                Spacer()
                Button("Cancel", action: cancel).buttonStyle(.borderless).font(.caption)
                Button("Save") { save(profile) }
                    .font(.caption.weight(.semibold))
                    .disabled(!profile.isDefault && profile.name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func resize(_ n: Int) {
        if n > profile.temps.count {
            profile.temps += Array(repeating: profile.temps.last ?? 26, count: n - profile.temps.count)
        } else {
            profile.temps = Array(profile.temps.prefix(n))
        }
    }
}

struct SleepScheduleEditor: View {
    let device: ACDevice
    let profile: SleepProfile
    let existing: SleepSchedule?
    let save: (Int, Int, Set<Int>) -> Void
    let remove: (String) -> Void
    let cancel: () -> Void

    @ViewState private var time = Calendar.current.date(bySettingHour: 22, minute: 30, second: 0, of: Date())!
    @ViewState private var days: Set<Int> = Set(1...7)

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Start “\(profile.displayName)” at").font(.caption.weight(.semibold))
            DatePicker("Time", selection: $time, displayedComponents: .hourAndMinute)
                .labelsHidden()
                .datePickerStyle(.field)
            HStack(spacing: 4) {
                ForEach(1...7, id: \.self) { d in
                    OptionTile(title: String(Calendar.current.veryShortWeekdaySymbols[d - 1]), symbol: "",
                               selected: days.contains(d), compact: true) {
                        if days.contains(d) { days.remove(d) } else { days.insert(d) }
                    }
                    .accessibilityLabel(Calendar.current.weekdaySymbols[d - 1])
                }
            }
            Text(days.isEmpty ? "Runs once" : days.count == 7 ? "Every day" : "Selected days")
                .font(.caption2).foregroundStyle(.secondary)
            HStack {
                if let existing {
                    Button("Remove schedule", role: .destructive) { remove(existing.ruleId) }
                        .buttonStyle(.borderless).font(.caption)
                }
                Spacer()
                Button("Cancel", action: cancel).buttonStyle(.borderless).font(.caption)
                Button("Save") {
                    let c = Calendar.current.dateComponents([.hour, .minute], from: time)
                    save(c.hour ?? 22, c.minute ?? 30, days)
                }
                .font(.caption.weight(.semibold))
            }
        }
        .onAppear {
            if let existing {
                time = Calendar.current.date(bySettingHour: existing.hour, minute: existing.minute, second: 0, of: Date()) ?? time
                days = existing.weekdays
            }
        }
    }
}

// MARK: Filter

struct ACFilterPanel: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice
    @ViewState private var confirm = false
    @ViewState private var busy = false
    @ViewState private var result: String?

    private var raw: [String: JSONValue] { device.state.raw }
    private var needsCleaning: Bool {
        switch raw["filterCleaningRequired"] {
        case .bool(let b): b
        case .string(let s): s.lowercased() == "true"
        default: false
        }
    }
    private var timeBased: Bool { device.capability("TimeBasedFilterHealth") != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Filter").font(.caption.weight(.semibold))
                Spacer()
                if timeBased {
                    Label("MirAIe cloud", systemImage: "cloud").font(.caption2).foregroundStyle(.secondary)
                }
            }
            Label(needsCleaning ? "Needs cleaning" : "Clean",
                  systemImage: needsCleaning ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .font(.callout.weight(.semibold))
                .foregroundStyle(needsCleaning ? Color.orange : Color.green)
            if let dust = raw["filterDustLevel"]?.intValue, !timeBased {
                Text("Dust level \(dust)%").font(.caption).foregroundStyle(.secondary)
            }
            if let hours = raw["totalOperatingHours"]?.doubleValue, let t = device.filterThresholds {
                VStack(alignment: .leading, spacing: 3) {
                    let color: Color = hours >= t.alert ? .red : hours >= t.warning ? .orange : hours >= t.reminder ? .yellow : .green
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.primary.opacity(0.08))
                            Capsule().fill(color).frame(width: geo.size.width * min(1, hours / t.alert))
                        }
                    }
                    .frame(height: 6)
                    .accessibilityLabel("Filter use \(Int(hours)) of \(Int(t.alert)) hours")
                    HStack {
                        Text("\(Int(hours)) h since last reset").monospacedDigit()
                        Spacer()
                        Text("Reminder \(Int(t.reminder)) h · alert \(Int(t.alert)) h").monospacedDigit()
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                }
            }
            if let result { Text(result).font(.caption).foregroundStyle(.secondary) }
            HStack {
                Spacer()
                if busy { ProgressView().controlSize(.small) }
                Button("Reset filter…") { confirm = true }
                    .font(.caption.weight(.medium))
                    .disabled(busy || (timeBased && ac.cloudBlocker != nil))
                    .help(timeBased && ac.cloudBlocker != nil ? ac.cloudBlocker! : "Do this after you've cleaned the filter")
            }
        }
        .confirmationDialog("Reset the filter reminder?", isPresented: $confirm) {
            Button("Reset") { Task { await reset() } }
        } message: {
            Text("Only do this after cleaning the filter. The cleaning reminder starts counting again.")
        }
    }

    private func reset() async {
        busy = true
        defer { busy = false }
        do {
            try await ac.resetFilter(device)
            result = "Filter reset."
        } catch {
            result = "Reset failed: \(error.localizedDescription)"
        }
    }
}

// MARK: Settings (rename, move, firmware)

struct ACSettingsPanel: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice
    @ViewState private var name = ""
    @ViewState private var status: String?
    @ViewState private var firmware: FirmwareInfo?
    @ViewState private var checking = false

    var body: some View {
        CloudGate(title: "AC settings") {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 6) {
                    TextField("Name", text: $name).textFieldStyle(.roundedBorder).onSubmit(rename)
                    Button("Rename", action: rename)
                        .font(.caption)
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || name == device.name || device.spaceId == nil)
                }
                if let spaces = ac.homeSpaces[device.homeId], spaces.count > 1, let current = device.spaceId {
                    Picker("Room", selection: Binding(get: { current }, set: { id in
                        if let s = spaces.first(where: { $0.id == id }) { Task { await act { try await ac.move(device, to: s) } } }
                    })) {
                        ForEach(spaces) { Text($0.name).tag($0.id) }
                    }
                    .font(.caption)
                }
                HStack(spacing: 6) {
                    Text("Firmware \(device.state.raw["V"]?.stringValue ?? "unknown")").font(.caption).monospacedDigit()
                    Spacer()
                    if checking { ProgressView().controlSize(.small) }
                    Button("Check for update") { Task { await check() } }.font(.caption).disabled(checking)
                }
                if let f = firmware {
                    Text(f.isLatest ? "Up to date." : "Update available \(f.latest ?? "") — install it from the MirAIe app.")
                        .font(.caption)
                        .foregroundStyle(f.isLatest ? Color.secondary : Color.orange)
                }
                if let status { Text(status).font(.caption).foregroundStyle(.secondary) }
            }
        }
        .onAppear { name = device.name }
    }

    private func rename() {
        Task { await act { try await ac.rename(device, to: name) } }
    }

    private func check() async {
        checking = true
        defer { checking = false }
        do { firmware = try await ac.checkFirmware(device) } catch { status = "Update check failed: \(error.localizedDescription)" }
    }

    private func act(_ work: () async throws -> Void) async {
        do {
            try await work()
            status = "Saved."
        } catch {
            status = "Failed: \(error.localizedDescription)"
        }
    }
}

// MARK: Active sleep profile pill (on the card)

struct ACSleepStatus: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice

    var body: some View {
        if let id = device.activeSleepProfileId {
            let p = (ac.sleepProfiles[device.id] ?? []).first { ($0.isDefault ? "default" : $0.id) == id }
            HStack(spacing: 4) {
                StatusPill(text: "Sleep: \(p?.displayName ?? "profile")", color: .indigo, symbol: "moon.zzz.fill")
                if let p {
                    Button { ac.setSleepProfile(device, p, on: false) } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                    .buttonStyle(.pressable)
                    .help("Stop sleep profile")
                    .accessibilityLabel("Stop sleep profile")
                }
            }
        }
    }
}

// MARK: Notifications

struct ACNotificationsCard: View {
    @EnvironmentObject private var ac: ACStore
    let close: () -> Void
    @ViewState private var page = 1
    @ViewState private var loading = false
    @ViewState private var more = true
    @ViewState private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("AC Notifications", systemImage: "bell").font(.subheadline.weight(.semibold))
                Spacer()
                Button("Hide", action: close).buttonStyle(.borderless).font(.caption)
            }
            CloudGate(title: "History") {
                VStack(alignment: .leading, spacing: 8) {
                    if let error { Label(error, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.red) }
                    if ac.notifications.isEmpty && !loading && error == nil {
                        Text("No notifications.").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(ac.notifications) { n in row(n) }
                    HStack {
                        Spacer()
                        if loading { ProgressView().controlSize(.small) }
                        if more && !ac.notifications.isEmpty {
                            Button("Load more") { Task { await load(page + 1) } }.font(.caption).disabled(loading)
                        }
                    }
                }
                .task { if ac.notifications.isEmpty { await load(1) } }
            }
        }
        .card()
    }

    private func row(_ n: ACNotification) -> some View {
        let (symbol, color): (String, Color) = switch n.severity {
        case "alert": ("exclamationmark.triangle.fill", .red)
        case "recommendation": ("lightbulb.fill", .yellow)
        default: ("info.circle.fill", .blue)
        }
        return HStack(alignment: .top, spacing: 8) {
            Image(systemName: symbol).foregroundStyle(color).font(.caption).frame(width: 14).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(n.title).font(.caption.weight(.semibold))
                if !n.message.isEmpty {
                    Text(n.message).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Text([n.deviceName, n.date?.formatted(date: .abbreviated, time: .shortened)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func load(_ p: Int) async {
        loading = true
        error = nil
        defer { loading = false }
        do {
            let added = try await ac.loadNotifications(page: p)
            page = p
            more = added >= 20
            if p == 1 { await ac.refreshUnreadCount() }
        } catch {
            self.error = "Couldn't load notifications: \(error.localizedDescription)"
        }
    }
}
