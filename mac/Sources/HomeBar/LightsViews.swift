import SwiftUI

struct LightsSection: View {
    @EnvironmentObject private var lights: LightsStore
    @EnvironmentObject private var nav: Navigator

    var body: some View {
        VStack(alignment: .leading, spacing: Metrics.sectionSpacing) {
            SectionHeader(
                title: "Lights",
                detail: lights.bulbs.isEmpty ? nil : "\(lights.bulbs.filter(\.on).count) of \(lights.bulbs.count) on",
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

            if !lights.presets.isEmpty {
                LightPresetsRow()
            }

            ForEach(lights.groups) { g in
                LightRoomSection(group: g)
            }

            let others = lights.ungrouped
            if !others.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    if !lights.groups.isEmpty {
                        Text("Other lights").font(.subheadline.weight(.semibold))
                    }
                    BulbGrid(bulbs: others)
                }
            }

            if !lights.bulbs.isEmpty {
                NavRow(title: "Manage rooms", symbol: "square.split.bottomrightquarter") { nav.push(.rooms) }
                    .padding(.horizontal, 4)
            }
        }
    }
}

/// Saved presets as chips: click to apply, right-click to delete.
struct LightPresetsRow: View {
    @EnvironmentObject private var lights: LightsStore

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "sparkles").font(.caption).foregroundStyle(.secondary).frame(width: 16).accessibilityHidden(true)
            FlowLayout(spacing: 6) {
                ForEach(lights.presets) { p in
                    Button { lights.applyPreset(p) } label: {
                        Text(p.name)
                            .font(.caption.weight(.medium))
                            .lineLimit(1)
                            .padding(.horizontal, 10)
                            .frame(height: 24)
                            .background(Capsule().fill(Color.primary.opacity(0.08)))
                    }
                    .buttonStyle(.pressable)
                    .help("Apply “\(p.name)” to \(p.entries.count) light\(p.entries.count == 1 ? "" : "s") — right-click to delete")
                    .contextMenu {
                        Button("Delete “\(p.name)”", role: .destructive) { lights.deletePreset(p) }
                    }
                    .accessibilityLabel("Apply preset \(p.name)")
                }
            }
        }
    }
}

/// A room: header with on-count, scene menu, options and group power; group brightness; tile grid.
struct LightRoomSection: View {
    @EnvironmentObject private var lights: LightsStore
    let group: LightGroup
    @ViewState private var dimming: Double = 80
    @ViewState private var renaming = false
    @ViewState private var savingPreset = false
    @ViewState private var draft = ""

    var body: some View {
        let members = lights.members(group)
        let anyOn = members.contains { $0.on }
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                if renaming || savingPreset {
                    TextField(renaming ? "Room name" : "Preset name", text: $draft)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(commit)
                        .onExitCommand { renaming = false; savingPreset = false }
                    Button("Save", action: commit).controlSize(.small)
                } else {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(group.name).font(.subheadline.weight(.semibold))
                        Text("\(members.filter(\.on).count) of \(members.count) on")
                            .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                    }
                    Spacer()
                    Menu {
                        ForEach(LightsStore.sceneGroups, id: \.title) { sg in
                            Section(sg.title) {
                                ForEach(sg.ids, id: \.self) { id in
                                    Button(LightsStore.sceneName(id) ?? "Scene \(id)") { lights.setGroupScene(group, id) }
                                }
                            }
                        }
                    } label: { Text("Scene").font(.caption.weight(.medium)) }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                    .disabled(members.isEmpty)
                    Menu {
                        Button("Save as Preset…") { draft = group.name; savingPreset = true }.disabled(members.isEmpty)
                        Button("Rename Room…") { draft = group.name; renaming = true }
                        Divider()
                        Button("Delete Room", role: .destructive) { lights.deleteGroup(group) }
                    } label: {
                        Image(systemName: "ellipsis").font(.system(size: 12, weight: .medium))
                    }
                    .menuStyle(.borderlessButton)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .accessibilityLabel("\(group.name) options")
                    PowerButton(isOn: anyOn, tint: .orange, label: "\(group.name) lights") { lights.setGroupOn(group, !anyOn) }
                        .disabled(members.isEmpty)
                }
            }
            if !members.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "sun.min").accessibilityHidden(true)
                    Slider(value: $dimming, in: 10...100) { editing in
                        if !editing { lights.setGroupDimming(group, Int(dimming)) }
                    }
                    .tint(.orange)
                    .accessibilityLabel("\(group.name) brightness")
                    Text("\(Int(dimming))%").monospacedDigit().frame(width: 36, alignment: .trailing)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                BulbGrid(bulbs: members)
            } else {
                Text("No lights yet. Open a light and choose this room.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .onAppear {
            let on = members.filter(\.on)
            if !on.isEmpty { dimming = Double(on.map(\.dimming).reduce(0, +) / on.count) }
        }
    }

    private func commit() {
        if renaming { lights.renameGroup(group, draft) }
        if savingPreset { lights.savePreset(draft, bulbs: lights.members(group)) }
        renaming = false
        savingPreset = false
    }
}

struct BulbGrid: View {
    let bulbs: [Bulb]

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 2), spacing: 8) {
            ForEach(bulbs) { BulbTile(bulb: $0) }
        }
    }
}

struct NewRoomButton: View {
    @EnvironmentObject private var lights: LightsStore
    @ViewState private var editing = false
    @ViewState private var name = ""

    var body: some View {
        if editing {
            HStack(spacing: 6) {
                TextField("Room name (e.g. Bedroom)", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(save)
                    .onExitCommand { editing = false }
                Button("Cancel") { editing = false }.buttonStyle(.borderless).font(.caption)
                Button("Add", action: save).controlSize(.small).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        } else {
            Button { name = ""; editing = true } label: {
                Label("New room", systemImage: "plus").font(.callout)
            }
            .buttonStyle(.borderless)
        }
    }

    private func save() {
        lights.createGroup(name)
        editing = false
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

// MARK: Bulb tile

/// Grid tile: the glowing icon toggles power; the name row opens the bulb page.
struct BulbTile: View {
    @EnvironmentObject private var lights: LightsStore
    @EnvironmentObject private var nav: Navigator
    @Environment(\.colorScheme) private var colorScheme
    let bulb: Bulb

    static let padding: CGFloat = 10
    static let radius: CGFloat = Metrics.tileRadius + padding // 16, concentric with the 6 pt tile radius

    var body: some View {
        let light = bulb.lightColor
        VStack(alignment: .leading, spacing: 8) {
            Button { lights.setOn(bulb, !bulb.on) } label: {
                Image(systemName: bulb.on ? "lightbulb.fill" : "lightbulb")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(bulb.on ? Color.black.opacity(0.75) : Color.secondary)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(bulb.on ? AnyShapeStyle(light) : AnyShapeStyle(Color.primary.opacity(0.08))))
                    .shadow(color: bulb.on ? light.opacity(0.5) : .clear, radius: 6)
            }
            .buttonStyle(.pressable)
            .disabled(!bulb.reachable)
            .help(bulb.on ? "Turn off" : "Turn on")
            .accessibilityLabel("\(bulb.name) power")
            .accessibilityValue(bulb.on ? "On" : "Off")

            Button { nav.push(.bulb(bulb.id)) } label: {
                HStack(spacing: 4) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(bulb.name).font(.callout.weight(.semibold)).lineLimit(1)
                        Text(bulb.statusText)
                            .font(.caption)
                            .foregroundStyle(bulb.reachable ? Color.secondary : Color.red)
                            .monospacedDigit()
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)).foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.pressable)
            .help("Open \(bulb.name)")
            .accessibilityLabel("\(bulb.name), \(bulb.statusText). Open light settings")
        }
        .padding(Self.padding)
        .background {
            RoundedRectangle(cornerRadius: Self.radius, style: .continuous)
                .fill(.background.secondary)
                .overlay {
                    if colorScheme == .dark {
                        RoundedRectangle(cornerRadius: Self.radius, style: .continuous).fill(Color.white.opacity(0.04))
                    }
                }
                .overlay {
                    if bulb.on {
                        RoundedRectangle(cornerRadius: Self.radius, style: .continuous).fill(light.opacity(0.14))
                    }
                }
                .shadow(color: .black.opacity(0.06), radius: 0.5, y: 0.5)
                .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
        }
        .opacity(bulb.reachable ? 1 : 0.6)
        .animation(.easeOut(duration: 0.15), value: bulb.on)
    }
}

@MainActor
extension Bulb {
    var lightColor: Color {
        if let rgb {
            return Color(red: Double(rgb.r) / 255, green: Double(rgb.g) / 255, blue: Double(rgb.b) / 255)
        }
        // Approximate white temperature: warm amber → neutral → cool blue-white.
        let k = Double(temp ?? 3500)
        let t = min(1, max(0, (k - 2200) / (6500 - 2200)))
        return Color(red: 1, green: 0.78 + 0.2 * t, blue: 0.45 + 0.55 * t)
    }

    var statusText: String {
        guard reachable else { return "Unreachable" }
        guard on else { return "Off" }
        var detail = "\(dimming)%"
        if sceneId > 0, let scene = LightsStore.sceneName(sceneId) {
            detail += " · \(scene)"
        } else if rgb != nil {
            detail += " · Color"
        } else if let t = temp {
            detail += " · \(t)K"
        }
        return detail
    }
}

// MARK: Bulb page

/// Everything for one bulb: power + brightness, white, colour, scene + speed, organize.
struct BulbPage: View {
    @EnvironmentObject private var lights: LightsStore
    let bulb: Bulb

    @ViewState private var editingName = false
    @ViewState private var nameDraft = ""
    @ViewState private var dimming: Double = 100
    @ViewState private var kelvin: Double = 4000
    @ViewState private var speed: Double = 100
    @ViewState private var color: Color = .white
    @ViewState private var savingPreset = false
    @ViewState private var presetName = ""

    private static let swatches: [(Int, Int, Int)] = [
        (255, 59, 48), (255, 149, 0), (255, 214, 10), (52, 199, 89), (0, 199, 190), (10, 132, 255), (175, 82, 222), (255, 45, 85),
    ]

    var body: some View {
        let light = bulb.lightColor
        VStack(spacing: Metrics.sectionSpacing) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    Button { lights.setOn(bulb, !bulb.on) } label: {
                        Image(systemName: bulb.on ? "lightbulb.fill" : "lightbulb")
                            .font(.system(size: 20, weight: .medium))
                            .foregroundStyle(bulb.on ? Color.black.opacity(0.75) : Color.secondary)
                            .frame(width: 48, height: 48)
                            .background(Circle().fill(bulb.on ? AnyShapeStyle(light) : AnyShapeStyle(Color.primary.opacity(0.08))))
                            .shadow(color: bulb.on ? light.opacity(0.5) : .clear, radius: 8)
                    }
                    .buttonStyle(.pressable)
                    .disabled(!bulb.reachable)
                    .help(bulb.on ? "Turn off" : "Turn on")
                    .accessibilityLabel("\(bulb.name) power")
                    .accessibilityValue(bulb.on ? "On" : "Off")
                    VStack(alignment: .leading, spacing: 1) {
                        Text(bulb.on ? "On" : "Off").font(.headline)
                        Text(bulb.statusText).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                    }
                    Spacer()
                }
                HStack(spacing: 8) {
                    Image(systemName: "sun.min").accessibilityHidden(true)
                    Slider(value: $dimming, in: 10...100) { editing in
                        if !editing { lights.setDimming(bulb, Int(dimming)) }
                    }
                    .tint(light)
                    .accessibilityLabel("Brightness")
                    Text("\(Int(dimming))%").monospacedDigit().frame(width: 36, alignment: .trailing)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            .card(tint: bulb.on ? light : nil)

            PageSection("White") {
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
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            PageSection("Color") {
                HStack(spacing: 6) {
                    ForEach(Array(Self.swatches.enumerated()), id: \.offset) { _, rgb in
                        swatch(rgb)
                    }
                    Spacer(minLength: 0)
                    ColorPicker("Custom color", selection: $color, supportsOpacity: false)
                        .labelsHidden()
                        .onChange(of: color) { _, newColor in sendColor(newColor) }
                        .help("Custom color")
                }
            }

            PageSection("Scene") {
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
                if LightsStore.movingScenes.contains(bulb.sceneId) {
                    HStack(spacing: 8) {
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
            }

            PageSection("Organize") {
                VStack(spacing: 8) {
                    HStack {
                        Text("Name").font(.callout)
                        Spacer()
                        if editingName {
                            TextField("Name", text: $nameDraft)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 160)
                                .onSubmit(commitRename)
                                .onExitCommand { editingName = false }
                            Button("Save", action: commitRename).controlSize(.small)
                        } else {
                            Text(bulb.name).font(.callout).foregroundStyle(.secondary).lineLimit(1)
                            Button("Rename", action: startRename).controlSize(.small)
                        }
                    }
                    HStack {
                        Text("Room").font(.callout)
                        Spacer()
                        Picker("Room", selection: Binding(get: { lights.group(of: bulb)?.id }, set: { id in
                            lights.assign(bulb, to: lights.groups.first { $0.id == id })
                        })) {
                            Text("None").tag(UUID?.none)
                            ForEach(lights.groups) { Text($0.name).tag(UUID?.some($0.id)) }
                        }
                        .labelsHidden()
                        .pickerStyle(.menu)
                        .fixedSize()
                        .disabled(lights.groups.isEmpty)
                        .help(lights.groups.isEmpty ? "Create a room first (Settings › Manage Rooms)" : "Move to a room")
                    }
                    if savingPreset {
                        HStack(spacing: 6) {
                            TextField("Preset name", text: $presetName)
                                .textFieldStyle(.roundedBorder)
                                .onSubmit(savePreset)
                                .onExitCommand { savingPreset = false }
                            Button("Save", action: savePreset).controlSize(.small)
                        }
                    }
                    HStack(spacing: 8) {
                        Button { lights.identify(bulb) } label: { Label("Identify", systemImage: "wave.3.right") }
                            .help("Blink this bulb so you can spot it")
                        Button { presetName = bulb.name; savingPreset = true } label: { Label("Save Preset", systemImage: "sparkles") }
                            .help("Save this light's current colour, brightness and scene")
                        Spacer()
                        Text(bulb.ip).font(.caption).foregroundStyle(.tertiary).monospacedDigit().textSelection(.enabled)
                    }
                    .controlSize(.small)
                }
            }
        }
        .onAppear(perform: syncFromBulb)
        .onChange(of: bulb) { _, _ in syncFromBulb() }
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

    private func savePreset() {
        lights.savePreset(presetName, bulbs: [bulb])
        savingPreset = false
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
