import SwiftUI

/// Renders the pushed route: back bar + independently scrolling content (docs/UI.md).
struct PageView: View {
    @EnvironmentObject private var ac: ACStore
    @EnvironmentObject private var lights: LightsStore
    @EnvironmentObject private var nav: Navigator
    let route: Route

    var body: some View {
        VStack(spacing: 0) {
            PageBar(title: title, subtitle: subtitle)
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 12)
            PanelScroll {
                content
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private var device: ACDevice? {
        if case .ac(let id, _) = route { return ac.devices.first { $0.id == id } }
        return nil
    }

    private var bulb: Bulb? {
        if case .bulb(let id) = route { return lights.bulbs.first { $0.id == id } }
        return nil
    }

    private var title: String {
        switch route {
        case .ac(_, let page): page == .more ? "More" : page.title
        case .bulb: bulb?.name ?? "Light"
        case .notifications: "AC Notifications"
        case .diagnostics: "AC Diagnostics"
        case .keys: "AC Keys"
        case .rooms: "Rooms"
        }
    }

    private var subtitle: String? {
        switch route {
        case .ac: device.map { [$0.name, $0.space].filter { !$0.isEmpty }.joined(separator: " · ") }
        case .bulb: bulb.map { lights.group(of: $0)?.name ?? "No room" }
        case .notifications: "Alerts, info and tips from MirAIe"
        case .diagnostics: "Model and status for troubleshooting"
        case .keys: "Local control without sign-in"
        case .rooms: "Group lights to control them together"
        }
    }

    @ViewBuilder
    private var content: some View {
        switch route {
        case .ac(_, let page):
            if let device {
                switch page {
                case .timer: ACTimerPage(device: device)
                case .sleep: ACSleepPanel(device: device).card()
                case .air: ACAirPage(device: device)
                case .energy: ACEnergyPanel(device: device).card()
                case .more: ACMorePage(device: device)
                }
            } else {
                missing("This AC is no longer available.")
            }
        case .bulb:
            if let bulb { BulbPage(bulb: bulb) } else { missing("This light is no longer available.") }
        case .notifications: ACNotificationsCard()
        case .diagnostics: ACDiagnosticsCard()
        case .keys: ACKeysCard()
        case .rooms: RoomsPage()
        }
    }

    private func missing(_ text: String) -> some View {
        EmptyStateView(symbol: "questionmark.circle", title: "Not found", message: text, actionTitle: "Back") { nav.pop() }
    }
}

// MARK: More

/// Filter, profiles, AC settings and links to notifications / diagnostics / keys.
struct ACMorePage: View {
    @EnvironmentObject private var ac: ACStore
    @EnvironmentObject private var nav: Navigator
    let device: ACDevice

    var body: some View {
        VStack(spacing: Metrics.sectionSpacing) {
            if device.panels.contains(.filter) {
                ACFilterPanel(device: device).card()
            }

            PageSection("Profiles") {
                let profiles = ac.profiles[device.id] ?? []
                if profiles.isEmpty {
                    Text("Save the current settings to switch back to them in one click.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                ForEach(profiles) { p in
                    HStack(spacing: 10) {
                        Image(systemName: "bookmark")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                            .frame(width: 18)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(p.name).font(.callout)
                            Text(ACProfilesRow.summary(p)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Apply") { Task { await ac.applyProfile(device, p) } }
                            .controlSize(.small)
                        Button(role: .destructive) { ac.deleteProfile(device, p) } label: { Image(systemName: "trash") }
                            .buttonStyle(.borderless)
                            .help("Delete profile")
                            .accessibilityLabel("Delete \(p.name)")
                    }
                }
                SaveProfileRow(device: device)
            }

            ACSettingsPanel(device: device).card()

            PageSection(nil) {
                VStack(spacing: 0) {
                    NavRow(title: "Notifications", symbol: "bell",
                           badge: ac.unreadAlerts > 0 ? "\(ac.unreadAlerts)" : nil) { nav.push(.notifications) }
                    Divider().padding(.leading, 28)
                    NavRow(title: "Diagnostics", symbol: "stethoscope") { nav.push(.diagnostics) }
                    if !ac.exportableDevices.isEmpty {
                        Divider().padding(.leading, 28)
                        NavRow(title: "LAN keys", symbol: "key") { nav.push(.keys) }
                    }
                }
            }
        }
    }
}

// MARK: Rooms

/// Create, rename and delete local light rooms.
struct RoomsPage: View {
    @EnvironmentObject private var lights: LightsStore
    @ViewState private var renaming: UUID?
    @ViewState private var draft = ""

    var body: some View {
        VStack(spacing: Metrics.sectionSpacing) {
            PageSection(nil) {
                if lights.groups.isEmpty {
                    Text("No rooms yet.").font(.caption).foregroundStyle(.secondary)
                }
                ForEach(lights.groups) { g in
                    HStack(spacing: 10) {
                        Image(systemName: "square.split.bottomrightquarter")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                            .frame(width: 18)
                        if renaming == g.id {
                            TextField("Room name", text: $draft)
                                .textFieldStyle(.roundedBorder)
                                .onSubmit { lights.renameGroup(g, draft); renaming = nil }
                                .onExitCommand { renaming = nil }
                            Button("Save") { lights.renameGroup(g, draft); renaming = nil }.controlSize(.small)
                        } else {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(g.name).font(.callout)
                                let n = lights.members(g).count
                                Text("\(n) light\(n == 1 ? "" : "s")").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Rename") { draft = g.name; renaming = g.id }.controlSize(.small)
                            Button(role: .destructive) { lights.deleteGroup(g) } label: { Image(systemName: "trash") }
                                .buttonStyle(.borderless)
                                .help("Delete room")
                                .accessibilityLabel("Delete \(g.name)")
                        }
                    }
                }
                NewRoomButton()
            }
            Text("Assign a light to a room from the light's page.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
