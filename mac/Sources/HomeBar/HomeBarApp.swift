import ServiceManagement
import SwiftUI

@main
struct HomeBarApp: App {
    @StateObject private var ac = ACStore()
    @StateObject private var lights = LightsStore()
    @StateObject private var nav = Navigator()

    init() {
        #if DEBUG
        Snapshot.runIfRequested()
        #endif
    }

    var body: some Scene {
        MenuBarExtra {
            ContentView()
                .environmentObject(ac)
                .environmentObject(lights)
                .environmentObject(nav)
        } label: {
            Image(systemName: ac.anyOn ? "air.conditioner.horizontal.fill"
                  : lights.anyOn ? "lightbulb.fill" : "house")
        }
        .menuBarExtraStyle(.window)
    }
}

enum HomeTab: Int, CaseIterable {
    case climate, lights

    var title: String { self == .climate ? "Climate" : "Lights" }
    var symbol: String { self == .climate ? "air.conditioner.horizontal" : "lightbulb" }
}

struct ContentView: View {
    @EnvironmentObject private var nav: Navigator
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage("selectedTab") private var tabRaw = 0

    /// Height of the scrolling list area (docs/UI.md: 540 pt).
    static let listHeight: CGFloat = 540
    #if DEBUG
    /// Snapshot harness: let the list grow to its full height.
    nonisolated(unsafe) static var debugUnbounded = false
    #endif

    private var tab: HomeTab { HomeTab(rawValue: tabRaw) ?? .climate }

    var body: some View {
        ZStack(alignment: .top) {
            // The tab list stays alive under a pushed page so Back restores its scroll position.
            home
                .opacity(nav.route == nil ? 1 : 0)
                .allowsHitTesting(nav.route == nil)
                .accessibilityHidden(nav.route != nil)
            if let route = nav.route {
                PageView(route: route)
                    .id(route)
                    .transition(.page(reduceMotion: reduceMotion))
            }
        }
        .frame(width: 380)
    }

    private var home: some View {
        VStack(spacing: 0) {
            HeaderView()
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 12)

            TabSwitcher(selection: $tabRaw)
                .padding(.horizontal, 16)
                .padding(.bottom, 12)

            PanelScroll {
                Group {
                    switch tab {
                    case .climate: ACSection()
                    case .lights: LightsSection()
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
            .frame(height: listHeight)
        }
    }

    private var listHeight: CGFloat? {
        #if DEBUG
        if Self.debugUnbounded { return nil }
        #endif
        return Self.listHeight
    }
}

/// Vertical scroll area; the DEBUG snapshot harness lays content out at full height instead.
struct PanelScroll<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        #if DEBUG
        if ContentView.debugUnbounded {
            content()
        } else {
            ScrollView { content() }.scrollIndicators(.never)
        }
        #else
        ScrollView { content() }.scrollIndicators(.never)
        #endif
    }
}

struct HeaderView: View {
    @EnvironmentObject private var ac: ACStore
    @EnvironmentObject private var lights: LightsStore

    private var summary: String {
        var parts: [String] = []
        if ac.loggedIn {
            let on = ac.devices.filter(\.state.power).count
            parts.append(on == 0 ? "AC off" : on == 1 ? "1 AC on" : "\(on) ACs on")
        }
        if !lights.bulbs.isEmpty {
            let on = lights.bulbs.filter(\.on).count
            parts.append("\(on) of \(lights.bulbs.count) lights on")
        }
        return parts.isEmpty ? "Nothing connected yet" : parts.joined(separator: " · ")
    }

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                Text("Home").font(.title3.weight(.semibold))
                Text(summary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Spacer()
            SettingsMenu()
            Button { NSApp.terminate(nil) } label: {
                Text("Quit")
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 10)
                    .frame(height: 28)
                    .background(Capsule().fill(Color.primary.opacity(0.08)))
            }
            .buttonStyle(.pressable)
            .help("Quit Home (⌘Q)")
        }
    }
}

struct SettingsMenu: View {
    @EnvironmentObject private var ac: ACStore
    @EnvironmentObject private var nav: Navigator
    @AppStorage("selectedTab") private var tabRaw = 0
    @ViewState private var launchAtLogin = SMAppService.mainApp.status == .enabled

    var body: some View {
        Menu {
            Toggle("Launch at Login", isOn: Binding(get: { launchAtLogin }, set: setLaunchAtLogin))
            if ac.loggedIn {
                Section("Air conditioners") {
                    Toggle("Local Network Only", isOn: $ac.localOnly)
                    Toggle("Try Local Control (Experimental)", isOn: $ac.tryLocal)
                    Button(ac.unreadAlerts > 0 ? "Notifications (\(ac.unreadAlerts) new)…" : "Notifications…") { open(.notifications) }
                    Button("Diagnostics…") { open(.diagnostics) }
                    Button("LAN Keys…") { open(.keys) }
                        .disabled(ac.exportableDevices.isEmpty)
                    Button("Copy AC Key Code") {
                        if let code = ac.exportKeyCode() {
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(code, forType: .string)
                        }
                    }
                    .disabled(ac.exportableDevices.isEmpty)
                    Button("Sign Out of MirAIe") { ac.logout(); nav.pop() }
                }
            }
            Section("Lights") {
                Button("Manage Rooms…") { tabRaw = HomeTab.lights.rawValue; nav.push(.rooms) }
            }
            Divider()
            Button("Quit Home") { NSApp.terminate(nil) }
                .keyboardShortcut("q")
        } label: {
            Image(systemName: "gearshape")
                .font(.system(size: 13, weight: .medium))
                .frame(width: 28, height: 28)
                .background(Circle().fill(Color.primary.opacity(0.08)))
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Settings")
        .accessibilityLabel("Settings")
    }

    private func open(_ r: Route) {
        tabRaw = HomeTab.climate.rawValue
        nav.push(r)
    }

    private func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {}
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }
}

/// Segmented control drawn as a pill track. Tab switches are frequent, so selection changes instantly.
struct TabSwitcher: View {
    @Environment(\.colorScheme) private var colorScheme
    @Binding var selection: Int

    var body: some View {
        HStack(spacing: 2) {
            ForEach(HomeTab.allCases, id: \.rawValue) { tab in
                let selected = selection == tab.rawValue
                Button { selection = tab.rawValue } label: {
                    Label(tab.title, systemImage: selected ? tab.symbol + ".fill" : tab.symbol)
                        .font(.callout.weight(selected ? .semibold : .regular))
                        .frame(maxWidth: .infinity, minHeight: 28)
                        .foregroundStyle(selected ? Color.primary : Color.secondary)
                        .background(
                            RoundedRectangle(cornerRadius: 7, style: .continuous)
                                .fill(selected ? (colorScheme == .dark ? Color.white.opacity(0.14) : Color(nsColor: .controlBackgroundColor)) : .clear)
                                .shadow(color: .black.opacity(selected ? 0.1 : 0), radius: 1.5, y: 0.5)
                        )
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(2) // outer radius 9 = inner 7 + padding 2
        .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Color.primary.opacity(0.07)))
    }
}
