import SwiftUI

struct LightsSection: View {
    @EnvironmentObject private var lights: LightsStore

    var body: some View {
        VStack(alignment: .leading, spacing: Metrics.sectionSpacing) {
            SectionHeader(
                title: "Lights",
                detail: lights.bulbs.isEmpty ? nil : "\(lights.bulbs.count) found",
                busy: lights.scanning,
                refreshHelp: "Scan for WiZ bulbs"
            ) { Task { await lights.discover() } }

            if !lights.bulbs.isEmpty {
                HStack(spacing: 8) {
                    QuickAction(title: "All On", symbol: "lightbulb.fill", tint: .yellow) { lights.setAll(true) }
                    QuickAction(title: "All Off", symbol: "lightbulb.slash", tint: .secondary) { lights.setAll(false) }
                }
            }

            if lights.bulbs.isEmpty && !lights.scanning {
                EmptyStateView(
                    symbol: "lightbulb.2",
                    title: "No bulbs found",
                    message: "Make sure this Mac is on the same Wi-Fi as your WiZ bulbs and that Local Network access is allowed in System Settings › Privacy & Security.",
                    actionTitle: "Scan Again"
                ) { Task { await lights.discover() } }
            }

            ForEach(lights.bulbs) { bulb in
                BulbCard(bulb: bulb)
            }
        }
    }
}

struct QuickAction: View {
    let title: String
    let symbol: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label {
                Text(title).foregroundStyle(.primary)
            } icon: {
                Image(systemName: symbol).foregroundStyle(tint == .secondary ? Color.primary : tint)
            }
            .font(.callout.weight(.medium))
            .frame(maxWidth: .infinity, minHeight: 32)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(tint.opacity(0.14)))
        }
        .buttonStyle(.pressable)
    }
}

struct BulbCard: View {
    @EnvironmentObject private var lights: LightsStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let bulb: Bulb

    #if DEBUG
    nonisolated(unsafe) static var debugExpandFirst = false
    @ViewState private var expanded = BulbCard.debugExpandFirst
    #else
    @ViewState private var expanded = false
    #endif
    @ViewState private var editingName = false
    @ViewState private var nameDraft = ""
    @ViewState private var dimming: Double = 100
    @ViewState private var kelvin: Double = 4000
    @ViewState private var speed: Double = 100
    @ViewState private var color: Color = .white

    private static let swatches: [(Int, Int, Int)] = [
        (255, 59, 48), (255, 149, 0), (255, 214, 10), (52, 199, 89), (0, 199, 190), (10, 132, 255), (175, 82, 222), (255, 45, 85),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header

            if bulb.on {
                brightnessRow
                    .transition(.reveal(reduceMotion: reduceMotion))
            }

            if expanded {
                details
                    .transition(.reveal(reduceMotion: reduceMotion))
            }
        }
        .card(tint: bulb.on ? lightColor : nil)
        .opacity(bulb.reachable ? 1 : 0.6)
        .animation(.easeOut(duration: 0.15), value: bulb.on)
        .onAppear(perform: syncFromBulb)
        .onChange(of: bulb) { _, _ in syncFromBulb() }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 10) {
            Button { lights.setOn(bulb, !bulb.on) } label: {
                Image(systemName: bulb.on ? "lightbulb.fill" : "lightbulb")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(bulb.on ? Color.black.opacity(0.75) : Color.secondary)
                    .frame(width: 36, height: 36)
                    .background(
                        Circle().fill(bulb.on ? AnyShapeStyle(lightColor) : AnyShapeStyle(Color.primary.opacity(0.08)))
                    )
                    .shadow(color: bulb.on ? lightColor.opacity(0.5) : .clear, radius: 6)
            }
            .buttonStyle(.pressable)
            .disabled(!bulb.reachable)
            .help(bulb.on ? "Turn off" : "Turn on")
            .accessibilityLabel("\(bulb.name) power")
            .accessibilityValue(bulb.on ? "On" : "Off")

            if editingName {
                TextField("Name", text: $nameDraft)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(commitRename)
                    .onExitCommand { editingName = false }
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    Text(bulb.name).font(.headline).lineLimit(1)
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(bulb.reachable ? Color.secondary : Color.red)
                        .monospacedDigit()
                        .lineLimit(1)
                }
                .contentShape(Rectangle())
                .onTapGesture(count: 2, perform: startRename)
            }

            Spacer(minLength: 4)

            Button { expanded.toggle() } label: {
                Image(systemName: "chevron.down")
                    .font(.system(size: 11, weight: .semibold))
                    .rotationEffect(.degrees(expanded ? 180 : 0))
                    .frame(width: 24, height: 24)
                    .background(Circle().fill(Color.primary.opacity(expanded ? 0.1 : 0.05)))
            }
            .buttonStyle(.pressable)
            .help(expanded ? "Fewer options" : "More options")
            .accessibilityLabel(expanded ? "Hide options" : "Show options")
        }
    }

    private var brightnessRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "sun.min").accessibilityHidden(true)
            Slider(value: $dimming, in: 10...100) { editing in
                if !editing { lights.setDimming(bulb, Int(dimming)) }
            }
            .tint(lightColor)
            .accessibilityLabel("Brightness")
            Text("\(Int(dimming))%")
                .monospacedDigit()
                .frame(width: 36, alignment: .trailing)
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }

    // MARK: Details

    private var details: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text("White").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Capsule()
                    .fill(LinearGradient(colors: [Color(red: 1, green: 0.72, blue: 0.42), .white, Color(red: 0.78, green: 0.88, blue: 1)],
                                         startPoint: .leading, endPoint: .trailing))
                    .frame(height: 6)
                    .overlay(Capsule().strokeBorder(Color.primary.opacity(0.1)))
                    .accessibilityHidden(true)
                HStack(spacing: 8) {
                    Text("Warm")
                    Slider(value: $kelvin, in: 2200...6500) { editing in
                        if !editing { lights.setTemp(bulb, Int(kelvin)) }
                    }
                    .accessibilityLabel("Color temperature")
                    Text("Cool")
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("Color").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    ForEach(Array(Self.swatches.enumerated()), id: \.offset) { _, rgb in
                        swatch(rgb)
                    }
                    Spacer(minLength: 0)
                    ColorPicker("Custom color", selection: $color, supportsOpacity: false)
                        .labelsHidden()
                        .onChange(of: color) { _, newColor in sendColor(newColor) }
                }
            }

            HStack {
                Text("Scene").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Spacer()
                Picker("Scene", selection: Binding(get: { bulb.sceneId }, set: { if $0 > 0 { lights.setScene(bulb, $0) } })) {
                    Text("None").tag(0)
                    ForEach(LightsStore.sceneGroups, id: \.title) { group in
                        Section(group.title) {
                            ForEach(group.ids, id: \.self) { id in
                                Text(LightsStore.sceneName(id) ?? "Scene \(id)").tag(id)
                            }
                        }
                    }
                }
                .labelsHidden()
                .pickerStyle(.menu)
                .fixedSize()
            }

            if LightsStore.movingScenes.contains(bulb.sceneId) {
                HStack(spacing: 8) {
                    Text("Speed").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Image(systemName: "tortoise").accessibilityHidden(true)
                    Slider(value: $speed, in: 10...200) { editing in
                        if !editing { lights.setSpeed(bulb, Int(speed)) }
                    }
                    .accessibilityLabel("Scene speed")
                    Image(systemName: "hare").accessibilityHidden(true)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            HStack(spacing: 12) {
                Text(bulb.ip).font(.caption2).foregroundStyle(.tertiary).monospacedDigit()
                Spacer()
                Button("Identify") { lights.identify(bulb) }
                    .buttonStyle(.borderless)
                    .font(.caption)
                    .help("Blink this bulb twice")
                Button("Rename", action: startRename)
                    .buttonStyle(.borderless)
                    .font(.caption)
            }
        }
    }

    private func swatch(_ rgb: (Int, Int, Int)) -> some View {
        let selected = bulb.rgb.map { $0.r == rgb.0 && $0.g == rgb.1 && $0.b == rgb.2 } ?? false
        let fill = Color(red: Double(rgb.0) / 255, green: Double(rgb.1) / 255, blue: Double(rgb.2) / 255)
        return Button { lights.setColor(bulb, r: rgb.0, g: rgb.1, b: rgb.2) } label: {
            Circle()
                .fill(fill)
                .frame(width: 22, height: 22)
                .overlay(Circle().strokeBorder(Color.primary.opacity(0.1)))
                .padding(2)
                .overlay(Circle().strokeBorder(selected ? Color.primary.opacity(0.85) : .clear, lineWidth: 2))
        }
        .buttonStyle(.pressable)
        .accessibilityLabel("Color \(rgb.0), \(rgb.1), \(rgb.2)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: Helpers

    private var lightColor: Color {
        if let rgb = bulb.rgb {
            return Color(red: Double(rgb.r) / 255, green: Double(rgb.g) / 255, blue: Double(rgb.b) / 255)
        }
        // Approximate white temperature: warm amber → neutral → cool blue-white.
        let k = Double(bulb.temp ?? 3500)
        let t = min(1, max(0, (k - 2200) / (6500 - 2200)))
        return Color(red: 1, green: 0.78 + 0.2 * t, blue: 0.45 + 0.55 * t)
    }

    private var statusText: String {
        guard bulb.reachable else { return "Unreachable" }
        guard bulb.on else { return "Off" }
        var detail = "\(bulb.dimming)%"
        if bulb.sceneId > 0, let scene = LightsStore.sceneName(bulb.sceneId) {
            detail += " · \(scene)"
        } else if bulb.rgb != nil {
            detail += " · Color"
        } else if let t = bulb.temp {
            detail += " · \(t)K"
        }
        return detail
    }

    private func startRename() {
        nameDraft = bulb.name
        editingName = true
    }

    private func commitRename() {
        lights.rename(bulb, to: nameDraft)
        editingName = false
    }

    private func syncFromBulb() {
        dimming = Double(bulb.dimming)
        speed = Double(bulb.speed)
        if let t = bulb.temp { kelvin = Double(t) }
    }

    private func sendColor(_ c: Color) {
        guard let ns = NSColor(c).usingColorSpace(.sRGB) else { return }
        lights.setColor(bulb, r: Int(ns.redComponent * 255), g: Int(ns.greenComponent * 255), b: Int(ns.blueComponent * 255))
    }
}
