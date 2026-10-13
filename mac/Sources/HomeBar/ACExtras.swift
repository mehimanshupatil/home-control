import SwiftUI

/// Wraps children onto new lines when they don't fit (info chips, value chips).
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for v in subviews {
            let size = v.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                y += rowHeight + spacing
                x = 0
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            maxX = max(maxX, x - spacing)
        }
        return CGSize(width: proposal.width ?? maxX, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for v in subviews {
            let size = v.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                y += rowHeight + spacing
                x = bounds.minX
                rowHeight = 0
            }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

// MARK: Status chips

/// Card chips for states that matter right now (docs/UI.md): timers, running sleep profile, Clean running,
/// filter alert, errors, warnings, PM2.5. Nothing is shown in the normal state.
struct ACStatusChips: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice

    var body: some View {
        let raw = device.state.raw
        let timers: [(ACStore.TimerSlot, Date)] = [ACStore.TimerSlot.off, .on].compactMap { s in
            ac.timerDate(device, s).map { (s, $0) }
        }
        let sleepId = device.activeSleepProfileId
        let sleep = sleepId.flatMap { id in (ac.sleepProfiles[device.id] ?? []).first { ($0.isDefault ? "default" : $0.id) == id } }
        let cleaning = device.isCleaning
        let filter = device.filterNeedsCleaning
        let errors = raw["errors"]?.stringValue?.trimmingCharacters(in: .whitespaces) ?? ""
        let warnings = raw["warnings"]?.stringValue?.trimmingCharacters(in: .whitespaces) ?? ""
        let pm: Int? = {
            guard let cap = device.capability("PM25Sensor"), cap.key != cap.name,
                  let v = raw[cap.key]?.doubleValue, v >= 0 else { return nil }
            return Int(v)
        }()
        let hasError = !errors.isEmpty && errors != "0"
        let any = !timers.isEmpty || sleepId != nil || cleaning || filter || hasError || !warnings.isEmpty || pm != nil

        if any {
            FlowLayout(spacing: 6) {
                ForEach(timers, id: \.0.rawValue) { slot, date in
                    StatusChip(text: "\(slot == .off ? "Off" : "On") at \(date.formatted(date: .omitted, time: .shortened))",
                               color: slot == .off ? .orange : .green, symbol: "timer",
                               closeLabel: "Cancel \(slot == .off ? "turn-off" : "turn-on") timer") {
                        ac.setTimer(device, slot, seconds: nil)
                    }
                }
                if sleepId != nil {
                    StatusChip(text: sleep?.displayName ?? "Sleep profile", color: .indigo, symbol: "moon.zzz.fill",
                               help: "Sleep profile running", closeLabel: "Stop sleep profile",
                               onClose: sleep.map { p in { ac.setSleepProfile(device, p, on: false) } })
                }
                if cleaning {
                    StatusChip(text: "Cleaning", color: .teal, symbol: "sparkles", help: "Evaporator clean is running")
                }
                if filter {
                    let cause = raw["filterCleaningCause"]?.stringValue ?? ""
                    StatusChip(text: "Clean filter", color: .orange, symbol: "line.3.horizontal.decrease",
                               help: cause.isEmpty ? "The filter needs cleaning" : cause)
                }
                if hasError {
                    StatusChip(text: "Error \(errors)", color: .red, symbol: "exclamationmark.triangle.fill")
                }
                if !warnings.isEmpty {
                    StatusChip(text: "Warning \(warnings)", color: .orange, symbol: "exclamationmark.circle.fill")
                }
                if let pm {
                    let band = Self.pm25Band(pm)
                    StatusChip(text: "PM2.5 \(pm) · \(band.label)",
                               color: band.level <= 1 ? .green : band.level == 2 ? .yellow : band.level == 3 ? .orange : .red,
                               symbol: band.level <= 1 ? "aqi.low" : band.level <= 3 ? "aqi.medium" : "aqi.high",
                               help: "Air quality: \(band.label)")
                }
            }
        }
    }

    /// `ACDevice.getAQIPM25Level` bands (µg/m³).
    static func pm25Band(_ v: Int) -> (label: String, level: Int) {
        switch v {
        case 0...30: ("Excellent", 0)
        case 31...60: ("Good", 1)
        case 61...90: ("Moderate", 2)
        case 91...120: ("Poor", 3)
        case 121...250: ("Unhealthy", 4)
        default: ("Hazardous", 5)
        }
    }
}

extension ACDevice {
    var isCleaning: Bool {
        guard let key = capability("EvaporatorClean")?.key else { return false }
        return state.raw[key]?.stringValue?.lowercased() == "on"
    }

    var filterNeedsCleaning: Bool {
        switch state.raw["filterCleaningRequired"] {
        case .bool(let b): b
        case .string(let s): s.lowercased() == "true"
        default: false
        }
    }

    /// Detail pages for this model, in action-bar order.
    var actionPages: [ACPage] {
        var out: [ACPage] = []
        if timerRange != nil { out.append(.timer) }
        if capability("SleepProfile") != nil { out.append(.sleep) }
        out.append(.air)
        if panels.contains(.energy) { out.append(.energy) }
        out.append(.more)
        return out
    }
}

// MARK: Action bar

/// Buttons that open the AC's detail pages. Circular icon wells (not option tiles) so they read as
/// navigation; a dot marks pages with something active, a cloud glyph marks cloud-only pages.
struct ACActionBar: View {
    @EnvironmentObject private var ac: ACStore
    @EnvironmentObject private var nav: Navigator
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let device: ACDevice

    private func active(_ p: ACPage) -> Bool {
        switch p {
        case .timer: ac.timerDate(device, .off) != nil || ac.timerDate(device, .on) != nil
        case .sleep: device.activeSleepProfileId != nil
        case .air: device.isCleaning
        case .energy: false
        case .more: device.filterNeedsCleaning
        }
    }

    var body: some View {
        // Fixed five columns so buttons keep their positions across cards with fewer pages.
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 5), spacing: 4) {
            ForEach(device.actionPages) { p in
                let on = active(p)
                Button { nav.push(.ac(device.id, p)) } label: {
                    VStack(spacing: 4) {
                        Image(systemName: p.symbol)
                            .font(.system(size: 14, weight: .medium))
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(Color.primary.opacity(0.07)))
                            .overlay(alignment: .topTrailing) {
                                if on {
                                    Circle()
                                        .fill(Color.accentColor)
                                        .frame(width: 8, height: 8)
                                        .transition(reduceMotion ? .opacity : .scale(scale: 0.25).combined(with: .opacity))
                                }
                            }
                            .overlay(alignment: .bottomTrailing) {
                                if p.cloud {
                                    Image(systemName: "cloud.fill")
                                        .font(.system(size: 7, weight: .semibold))
                                        .foregroundStyle(.secondary)
                                        .offset(x: 2, y: 1)
                                }
                            }
                            .animation(.timingCurve(0.2, 0, 0, 1, duration: 0.3), value: on)
                        Text(p.title)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 2)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.pressable)
                .help(p.cloud ? "\(p.title) (uses MirAIe cloud)" : p.title)
                .accessibilityLabel(p.title + (on ? ", active" : ""))
            }
        }
    }
}

// MARK: Timer page

/// Turn-on / turn-off timer. Both slots can be set; the selector defaults to "Turn off" while the AC runs.
struct ACTimerPage: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice

    @ViewState private var customMinutes = 90
    @ViewState private var chosenSlot: ACStore.TimerSlot?

    private var slot: ACStore.TimerSlot { chosenSlot ?? (device.state.power ? .off : .on) }
    private var range: ACRange { device.timerRange ?? ACRange(min: 10, max: 1440, step: 10) }
    private var presets: [Int] { [30, 60, 120, 240, 480].filter { Double($0) >= range.min && Double($0) <= range.max } }

    var body: some View {
        let active: [(ACStore.TimerSlot, Date)] = [ACStore.TimerSlot.off, .on].compactMap { s in
            ac.timerDate(device, s).map { (s, $0) }
        }
        VStack(spacing: Metrics.sectionSpacing) {
            PageSection("New timer") {
                Picker("Timer type", selection: Binding(get: { slot }, set: { chosenSlot = $0 })) {
                    Text("Turn on").tag(ACStore.TimerSlot.on)
                    Text("Turn off").tag(ACStore.TimerSlot.off)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                Text(slot == .off ? "Turn the AC off in" : "Turn the AC on in")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 3), spacing: 4) {
                    ForEach(presets, id: \.self) { m in
                        TileButton(title: Self.duration(m)) { ac.setTimer(device, slot, seconds: m * 60) }
                    }
                }
                HStack(spacing: 8) {
                    Stepper(value: $customMinutes, in: Int(range.min)...Int(range.max), step: Int(range.step)) {
                        Text(Self.duration(customMinutes)).font(.callout).monospacedDigit()
                    }
                    Spacer()
                    Button("Set Timer") { ac.setTimer(device, slot, seconds: customMinutes * 60) }
                        .controlSize(.small)
                }
            }

            PageSection("Active") {
                if active.isEmpty {
                    Text("No timers set.").font(.caption).foregroundStyle(.secondary)
                }
                ForEach(active, id: \.0.rawValue) { s, date in
                    HStack(spacing: 10) {
                        Image(systemName: "timer")
                            .font(.system(size: 13))
                            .foregroundStyle(s == .off ? Color.orange : Color.green)
                            .frame(width: 18)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Turns \(s == .off ? "off" : "on") at \(date.formatted(date: .omitted, time: .shortened))")
                                .font(.callout)
                            Text("in " + Self.duration(max(1, Int(date.timeIntervalSinceNow / 60))))
                                .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        }
                        Spacer()
                        Button("Cancel") { ac.setTimer(device, s, seconds: nil) }
                            .controlSize(.small)
                            .accessibilityLabel("Cancel \(s == .off ? "turn-off" : "turn-on") timer")
                    }
                }
            }
        }
        .onAppear { customMinutes = Int(min(range.max, max(range.min, 90))) }
    }

    static func duration(_ minutes: Int) -> String {
        let h = minutes / 60, m = minutes % 60
        switch (h, m) {
        case (0, _): return "\(m) min"
        case (_, 0): return "\(h) h"
        default: return "\(h) h \(m) min"
        }
    }
}

/// Neutral action tile (timer presets). Actions, not selections, so no selected state;
/// primary text on a neutral fill keeps contrast in both themes.
struct TileButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.callout.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, minHeight: 32)
                .background(RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous).fill(Color.primary.opacity(0.08)))
        }
        .buttonStyle(.pressable)
    }
}

// MARK: Profiles

/// Saved local profiles as chips: click to apply, right-click to delete.
struct ACProfilesRow: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "bookmark").font(.caption).foregroundStyle(.secondary).frame(width: 16)
                .accessibilityHidden(true)
            FlowLayout(spacing: 6) {
                ForEach(ac.profiles[device.id] ?? []) { p in
                    Button { Task { await ac.applyProfile(device, p) } } label: {
                        Text(p.name)
                            .font(.caption.weight(.medium))
                            .padding(.horizontal, 10)
                            .frame(height: 24)
                            .background(Capsule().fill(Color.primary.opacity(0.08)))
                    }
                    .buttonStyle(.pressable)
                    .help(Self.summary(p) + " — right-click to delete")
                    .contextMenu {
                        Button("Delete “\(p.name)”", role: .destructive) { ac.deleteProfile(device, p) }
                    }
                    .accessibilityLabel("Apply profile \(p.name)")
                }
            }
        }
    }

    static func summary(_ p: ACProfile) -> String {
        guard p.power else { return "Turns the AC off" }
        let t = p.temp == p.temp.rounded() ? String(format: "%.0f°", p.temp) : String(format: "%.1f°", p.temp)
        return "\(p.mode.capitalized), \(t), fan \(p.fan)"
    }
}

// MARK: Air page

/// Model-driven air controls in grouped sections: air flow, display, capacity, features.
struct ACAirPage: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice

    private var tint: Color { ACModeStyle.tint(device.state.mode) }
    private var muted: Bool { !device.state.power }

    var body: some View {
        let caps = device.capabilities
        let toggles = ACCapability.toggleNames.compactMap { n in caps.first { $0.name == n && $0.kind == .toggle } }
        let cycles = caps.filter { if case .cycle = $0.kind { return true } else { return false } }
        let choiceCaps = caps.filter { if case .choice = $0.kind { return true } else { return false } }
        let swings = ["VerticalSwing", "HorizontalSwing"].compactMap { n in caps.first { $0.name == n } }
        let capacity = ["Convertible", "ACGenMode"].compactMap { n in caps.first { $0.name == n } }
        let cleaning = device.isCleaning

        VStack(spacing: Metrics.sectionSpacing) {
            if caps.isEmpty {
                PageSection(nil) {
                    Label("Extra controls appear after the AC's model info loads (Refresh while signed in).", systemImage: "info.circle")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            if !toggles.isEmpty || !cycles.isEmpty {
                PageSection("Features") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 3), spacing: 4) {
                        ForEach(toggles) { cap in
                            let on = device.isOn(cap)
                            OptionTile(title: cap.label, symbol: cap.symbol, selected: on, tint: tint, muted: muted) {
                                ac.setToggle(device, cap, !on)
                            }
                            .help("\(cap.label): \(on ? "on" : "off")")
                        }
                        ForEach(cycles) { cap in
                            if case .cycle(let values) = cap.kind {
                                let value = device.state.raw[cap.key]?.stringValue?.lowercased() ?? "off"
                                let next = values[((values.firstIndex(of: value) ?? -1) + 1) % values.count]
                                OptionTile(title: value == "off" ? cap.label : "\(cap.label) \(value)", symbol: cap.symbol,
                                           selected: value != "off", tint: tint, muted: muted) {
                                    // Official app: SingleValueCapability.buildMessage(json, str) sends no `ki`.
                                    ac.sendFields(device, [cap.key: next], ki: -1)
                                    ac.update(device) { $0.raw[cap.key] = .string(next) }
                                }
                                .help("\(cap.label): \(value) — click for \(next)")
                            }
                        }
                    }
                }
            }

            if !swings.isEmpty {
                PageSection("Air flow") {
                    ForEach(swings) { cap in intChips(cap) }
                        .disabled(cleaning)
                        .opacity(cleaning ? 0.5 : 1)
                    if cleaning {
                        Label("Swing is locked while Clean runs", systemImage: "lock.fill")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if !choiceCaps.isEmpty {
                PageSection("Display") {
                    ForEach(choiceCaps) { cap in
                        if case .choice(let values) = cap.kind {
                            LabeledChips(title: choiceCaps.count > 1 ? cap.label : nil) {
                                ForEach(values, id: \.self) { v in
                                    OptionTile(title: v.capitalized, symbol: "", selected: device.state.raw[cap.key]?.stringValue == v,
                                               tint: tint, compact: true, muted: muted) {
                                        ac.send(device, capability: cap, value: v)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if !capacity.isEmpty {
                PageSection("Capacity") {
                    ForEach(capacity) { cap in intChips(cap, showTitle: capacity.count > 1) }
                }
            }
        }
    }

    @ViewBuilder
    private func intChips(_ cap: ACCapability, showTitle: Bool = true) -> some View {
        if case .intChoice(let options) = cap.kind, !options.isEmpty {
            LabeledChips(title: showTitle ? cap.label : nil) {
                ForEach(options, id: \.self) { opt in
                    OptionTile(title: opt.label, symbol: "", selected: device.state.raw[cap.key]?.intValue == opt.value,
                               tint: tint, compact: true, muted: muted) {
                        ac.send(device, capability: cap, value: opt.value)
                    }
                }
            }
        }
    }
}

/// "Save current settings as profile…" with an inline name field.
struct SaveProfileRow: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice
    @ViewState private var editing = false
    @ViewState private var name = ""

    var body: some View {
        if editing {
            HStack(spacing: 6) {
                TextField("Profile name (e.g. Night)", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(save)
                    .onExitCommand { editing = false }
                Button("Cancel") { editing = false }.buttonStyle(.borderless).font(.caption)
                Button("Save", action: save).font(.caption.weight(.semibold)).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        } else {
            Button {
                name = ""
                editing = true
            } label: {
                Label("Save current settings as profile…", systemImage: "square.and.arrow.down")
                    .font(.caption.weight(.medium))
            }
            .buttonStyle(.borderless)
        }
    }

    private func save() {
        ac.saveProfile(device, name: name)
        editing = false
    }
}

/// Caption above a row of equal-width chips that wraps into a grid past six options.
private struct LabeledChips<Content: View>: View {
    let title: String?
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let title { Text(title).font(.caption).foregroundStyle(.secondary) }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 6), spacing: 4) {
                content()
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(title ?? "")
    }
}

// MARK: Diagnostics

/// Shareable model + status dump for mapping your AC's exact keys. Secrets are excluded or masked.
struct ACDiagnosticsCard: View {
    @EnvironmentObject private var ac: ACStore
    @ViewState private var copied = false
    /// Shown with a Hide button when embedded; nil on its own page.
    var close: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let close {
                HStack {
                    Label("AC Diagnostics", systemImage: "stethoscope").font(.subheadline.weight(.semibold))
                    Spacer()
                    Button("Hide", action: close).buttonStyle(.borderless).font(.caption)
                }
            }
            Text("Safe to share: no password, no tokens, and LAN keys are masked.")
                .font(.caption)
                .foregroundStyle(.secondary)

            if ac.devices.isEmpty {
                Text("No ACs loaded yet.").font(.caption).foregroundStyle(.secondary)
            }

            ForEach(ac.devices) { d in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(d.name).font(.caption.weight(.semibold))
                        Spacer()
                        Text(d.transport.rawValue.capitalized).font(.caption2).foregroundStyle(.secondary)
                    }
                    Text("Model: \(d.modelId ?? "unknown")")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    ScrollView {
                        Text(Self.describe(d))
                            .font(.system(size: 10, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(8)
                    }
                    .frame(height: 150)
                    .background(RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous).fill(Color.primary.opacity(0.05)))
                }
            }

            Button {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(ac.diagnosticsJSON(), forType: .string)
                copied = true
                Task { try? await Task.sleep(for: .seconds(2)); copied = false }
            } label: {
                Label(copied ? "Copied" : "Copy Diagnostics", systemImage: copied ? "checkmark" : "doc.on.doc")
                    .contentTransition(.symbolEffect(.replace))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
        .card()
    }

    private static func describe(_ d: ACDevice) -> String {
        let caps = d.capabilities
        var lines: [String] = caps.isEmpty
            ? ["capabilities: model not loaded"]
            : caps.map { "\($0.name)  key=\($0.key)  ki=\($0.ki)  \($0.kindName)  [\($0.valuesSummary)]" }
        lines.append("")
        lines.append("last status:")
        lines.append(d.state.raw.isEmpty ? "(none yet)" : JSONValue.object(d.state.raw).prettyText())
        return lines.joined(separator: "\n")
    }
}
