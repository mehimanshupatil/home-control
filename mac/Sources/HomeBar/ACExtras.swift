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

// MARK: Info row

/// Read-only status chips from the keys real ACs report (PROTOCOL.md §1.8): Wi-Fi signal,
/// filter, errors and warnings, plus humidity / PM2.5 / power when the model declares them.
struct ACInfoRow: View {
    let device: ACDevice

    private enum Style { case plain, warning, fault }

    private struct Item: Identifiable {
        let id: String
        let text: String
        let symbol: String
        var style: Style = .plain
        var help: String? = nil
        var signal: Double? = nil
    }

    private var items: [Item] {
        let raw = device.state.raw
        func declared(_ name: String) -> JSONValue? {
            guard let cap = device.capability(name), cap.key != name else { return nil }
            return raw[cap.key]
        }
        func text(_ key: String) -> String {
            raw[key]?.stringValue?.trimmingCharacters(in: .whitespaces) ?? ""
        }
        var out: [Item] = []
        if let rssi = raw["rssi"]?.doubleValue, rssi < 0 {
            let bars = rssi >= -55 ? 3.0 : rssi >= -67 ? 2 : rssi >= -80 ? 1 : 0
            let word = ["Weak", "Weak", "Fair", "Good"][Int(bars)]
            out.append(Item(id: "rssi", text: "Wi-Fi \(word.lowercased())", symbol: "wifi",
                            help: String(format: "Wi-Fi signal %.0f dBm", rssi), signal: bars / 3))
        }
        if let h = declared("RoomHumidity")?.doubleValue {
            out.append(Item(id: "hum", text: String(format: "Humidity %.0f%%", h), symbol: "humidity"))
        }
        if let pm = declared("PM25Sensor")?.doubleValue {
            out.append(Item(id: "pm", text: String(format: "PM2.5 %.0f", pm), symbol: "aqi.low"))
        }
        if let w = declared("CurrentPowerConsumption")?.doubleValue {
            out.append(Item(id: "power", text: String(format: "%.0f W", w), symbol: "bolt"))
        }
        let cleanFilter: Bool = {
            switch raw["filterCleaningRequired"] {
            case .bool(let b): return b
            case .string(let s): return s.lowercased() == "true"
            default: return false
            }
        }()
        if cleanFilter {
            let cause = text("filterCleaningCause")
            out.append(Item(id: "filter", text: "Clean filter", symbol: "line.3.horizontal.decrease", style: .warning,
                            help: cause.isEmpty ? nil : cause))
        }
        if let dust = raw["filterDustLevel"]?.intValue, dust > 0 {
            out.append(Item(id: "dust", text: "Filter dust \(dust)", symbol: "line.3.horizontal.decrease"))
        }
        let warnings = text("warnings")
        if !warnings.isEmpty {
            out.append(Item(id: "warn", text: "Warning \(warnings)", symbol: "exclamationmark.circle.fill", style: .warning))
        }
        let errors = text("errors")
        if !errors.isEmpty, errors != "0" {
            out.append(Item(id: "error", text: "Error \(errors)", symbol: "exclamationmark.triangle.fill", style: .fault))
        }
        return out
    }

    var body: some View {
        let items = items
        if !items.isEmpty {
            FlowLayout(spacing: 6) {
                ForEach(items) { item in
                    Group {
                        switch item.style {
                        case .fault: StatusPill(text: item.text, color: .red, symbol: item.symbol)
                        case .warning: StatusPill(text: item.text, color: .orange, symbol: item.symbol)
                        case .plain:
                            Label {
                                Text(item.text)
                            } icon: {
                                if let signal = item.signal {
                                    Image(systemName: item.symbol, variableValue: signal)
                                } else {
                                    Image(systemName: item.symbol)
                                }
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(Color.primary.opacity(0.06)))
                        }
                    }
                    .help(item.help ?? item.text)
                }
            }
        }
    }
}

// MARK: Timer

/// Turn-on / turn-off timer with presets, a custom stepper and active-timer pills. Both slots can be set;
/// the picker defaults to "Turn off" while the AC runs and "Turn on" while it's off.
struct ACTimerRow: View {
    @EnvironmentObject private var ac: ACStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let device: ACDevice

    @ViewState private var customOpen = false
    @ViewState private var customMinutes = 90
    /// User's choice; nil follows the power state.
    @ViewState private var chosenSlot: ACStore.TimerSlot?

    private var slot: ACStore.TimerSlot { chosenSlot ?? (device.state.power ? .off : .on) }
    private var range: ACRange { device.timerRange ?? ACRange(min: 10, max: 1440, step: 10) }
    private var presets: [Int] { [30, 60, 120, 240, 480].filter { Double($0) >= range.min && Double($0) <= range.max } }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "timer").font(.caption).foregroundStyle(.secondary).frame(width: 16)
                    .accessibilityHidden(true)
                Picker("Timer type", selection: Binding(get: { slot }, set: { chosenSlot = $0 })) {
                    Text("Turn on").tag(ACStore.TimerSlot.on)
                    Text("Turn off").tag(ACStore.TimerSlot.off)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .controlSize(.small)
                .fixedSize()
                Text("in").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Menu {
                    ForEach(presets, id: \.self) { m in
                        Button(Self.duration(m)) { ac.setTimer(device, slot, seconds: m * 60) }
                    }
                    Divider()
                    Button("Custom…") {
                        customMinutes = Int(min(range.max, max(range.min, 90)))
                        customOpen = true
                    }
                } label: {
                    Text("Set timer").font(.caption.weight(.medium))
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .accessibilityLabel(slot == .off ? "Set turn-off timer" : "Set turn-on timer")
            }

            if customOpen {
                HStack(spacing: 8) {
                    Stepper(value: $customMinutes, in: Int(range.min)...Int(range.max), step: Int(range.step)) {
                        Text(Self.duration(customMinutes)).font(.caption).monospacedDigit()
                    }
                    Spacer()
                    Button("Cancel") { customOpen = false }.buttonStyle(.borderless).font(.caption)
                    Button("Set") {
                        ac.setTimer(device, slot, seconds: customMinutes * 60)
                        customOpen = false
                    }
                    .font(.caption.weight(.semibold))
                }
                .padding(.leading, 24)
                .transition(.reveal(reduceMotion: reduceMotion))
            }

            let active: [(ACStore.TimerSlot, Date)] = [ACStore.TimerSlot.off, .on].compactMap { s in
                ac.timerDate(device, s).map { (s, $0) }
            }
            if !active.isEmpty {
                FlowLayout(spacing: 6) {
                    ForEach(active, id: \.0.rawValue) { s, date in
                        HStack(spacing: 4) {
                            StatusPill(text: "\(s == .off ? "Off" : "On") at \(date.formatted(date: .omitted, time: .shortened))",
                                       color: s == .off ? .orange : .green, symbol: "timer")
                            Button { ac.setTimer(device, s, seconds: nil) } label: {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                            }
                            .buttonStyle(.pressable)
                            .help("Cancel timer")
                            .accessibilityLabel("Cancel \(s == .off ? "turn-off" : "turn-on") timer")
                        }
                    }
                }
                .padding(.leading, 24)
            }
        }
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

// MARK: Profiles

/// Saved local profiles as chips: click to apply, right-click to delete.
struct ACProfilesRow: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "square.stack").font(.caption).foregroundStyle(.secondary).frame(width: 16)
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

// MARK: More controls

/// Model-driven extra controls. Only capabilities the AC's model declares are shown.
struct ACMoreControls: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice
    let tint: Color

    private var caps: [ACCapability] { device.capabilities }
    private var muted: Bool { !device.state.power }

    var body: some View {
        let caps = caps
        let toggles = ACCapability.toggleNames.compactMap { n in caps.first { $0.name == n && $0.kind == .toggle } }
        let choiceCaps = caps.filter { if case .choice = $0.kind { return true } else { return false } }
        let intCaps = ["VerticalSwing", "HorizontalSwing", "Convertible", "ACGenMode"].compactMap { n in
            caps.first { $0.name == n }
        }

        let cleanKey = device.capability("EvaporatorClean")?.key
        let cleaning = cleanKey.map { device.state.raw[$0]?.stringValue?.lowercased() == "on" } ?? false

        VStack(alignment: .leading, spacing: 10) {
            if caps.isEmpty {
                Text("Extra controls appear after the AC's model info loads (Refresh while signed in).")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if !toggles.isEmpty {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 4), spacing: 4) {
                    ForEach(toggles) { cap in
                        let on = device.state.raw[cap.key]?.stringValue?.lowercased() == "on"
                        OptionTile(title: cap.label, symbol: cap.symbol, selected: on, tint: tint, muted: muted) {
                            ac.send(device, capability: cap, value: on ? "off" : "on")
                        }
                        .help("\(cap.label): \(on ? "on" : "off")")
                    }
                }
            }

            ForEach(choiceCaps) { cap in
                if case .choice(let values) = cap.kind {
                    LabeledChips(title: cap.label) {
                        ForEach(values, id: \.self) { v in
                            OptionTile(title: v.capitalized, symbol: "", selected: device.state.raw[cap.key]?.stringValue == v,
                                       tint: tint, compact: true, muted: muted) {
                                ac.send(device, capability: cap, value: v)
                            }
                        }
                    }
                }
            }

            ForEach(intCaps) { cap in
                if case .intChoice(let options) = cap.kind, !options.isEmpty {
                    let isSwing = cap.name.hasSuffix("Swing")
                    let lastSwing = intCaps.last(where: { $0.name.hasSuffix("Swing") })?.name == cap.name
                    LabeledChips(title: cap.label) {
                        ForEach(options, id: \.self) { opt in
                            OptionTile(title: opt.label, symbol: "", selected: device.state.raw[cap.key]?.intValue == opt.value,
                                       tint: tint, compact: true, muted: muted) {
                                ac.send(device, capability: cap, value: opt.value)
                            }
                        }
                    }
                    .disabled(isSwing && cleaning)
                    .opacity(isSwing && cleaning ? 0.5 : 1)
                    if lastSwing && cleaning {
                        Label("Swing is locked while Clean runs", systemImage: "lock.fill")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            SaveProfileRow(device: device)
        }
    }
}

/// "Save current settings as profile…" with an inline name field.
private struct SaveProfileRow: View {
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
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 6), spacing: 4) {
                content()
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(title)
    }
}

// MARK: Diagnostics

/// Shareable model + status dump for mapping your AC's exact keys. Secrets are excluded or masked.
struct ACDiagnosticsCard: View {
    @EnvironmentObject private var ac: ACStore
    @ViewState private var copied = false
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("AC Diagnostics", systemImage: "stethoscope").font(.subheadline.weight(.semibold))
                Spacer()
                Button("Hide", action: close).buttonStyle(.borderless).font(.caption)
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
