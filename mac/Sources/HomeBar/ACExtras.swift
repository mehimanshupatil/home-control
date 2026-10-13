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

/// Read-only status chips: humidity, filter, PM2.5, power and faults. Only values the AC reports.
struct ACInfoRow: View {
    let device: ACDevice

    private struct Item: Identifiable {
        let id: String
        let text: String
        let symbol: String
        var isFault = false
    }

    private var items: [Item] {
        let caps = device.capabilities
        let raw = device.state.raw
        func value(_ name: String) -> JSONValue? {
            guard let cap = caps.first(where: { $0.name == name }) else { return nil }
            return raw[cap.key]
        }
        var out: [Item] = []
        if let h = value("RoomHumidity")?.doubleValue {
            out.append(Item(id: "hum", text: String(format: "Humidity %.0f%%", h), symbol: "humidity"))
        }
        if let f = value("FilterDustLevel")?.intValue ?? value("TimeBasedFilterHealth")?.intValue {
            out.append(Item(id: "filter", text: "Filter \(f)", symbol: "line.3.horizontal.decrease"))
        }
        if let pm = value("PM25Sensor")?.doubleValue {
            out.append(Item(id: "pm", text: String(format: "PM2.5 %.0f", pm), symbol: "aqi.low"))
        }
        if let w = value("CurrentPowerConsumption")?.doubleValue {
            out.append(Item(id: "power", text: String(format: "%.0f W", w), symbol: "bolt"))
        }
        // Official app reads faults from "errors" (comma-separated); also honour the model's ACFaults key.
        let faultText = (value("ACFaults") ?? raw["errors"])?.stringValue?.trimmingCharacters(in: .whitespaces) ?? ""
        if !faultText.isEmpty, faultText != "0" {
            out.append(Item(id: "fault", text: "Fault \(faultText)", symbol: "exclamationmark.triangle.fill", isFault: true))
        }
        return out
    }

    var body: some View {
        let items = items
        if !items.isEmpty {
            FlowLayout(spacing: 6) {
                ForEach(items) { item in
                    if item.isFault {
                        StatusPill(text: item.text, color: .red, symbol: item.symbol)
                    } else {
                        Label(item.text, systemImage: item.symbol)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(Color.primary.opacity(0.06)))
                    }
                }
            }
        }
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
                    LabeledChips(title: cap.label) {
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
