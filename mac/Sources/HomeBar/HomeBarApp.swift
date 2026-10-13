import ServiceManagement
import SwiftUI

@main
struct HomeBarApp: App {
    @StateObject private var ac = ACStore()
    @StateObject private var lights = LightsStore()

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
    @AppStorage("selectedTab") private var tabRaw = 0

    private var tab: HomeTab { HomeTab(rawValue: tabRaw) ?? .climate }

    var body: some View {
        VStack(spacing: 0) {
            HeaderView()
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)

            TabSwitcher(selection: $tabRaw)
                .padding(.horizontal, 16)
                .padding(.bottom, 12)

            ScrollView {
                Group {
                    switch tab {
                    case .climate: ACSection()
                    case .lights: LightsSection()
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
            .scrollIndicators(.never)
            .frame(height: 470)
        }
        .frame(width: 380)
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
    @AppStorage("showACKeys") private var showACKeys = false
    @AppStorage("showACDiagnostics") private var showDiagnostics = false
    @AppStorage("showACNotifications") private var showNotifications = false
    @ViewState private var launchAtLogin = SMAppService.mainApp.status == .enabled

    var body: some View {
        Menu {
            Toggle("Launch at Login", isOn: Binding(get: { launchAtLogin }, set: setLaunchAtLogin))
            if ac.loggedIn {
                Toggle("AC: Local Network Only", isOn: $ac.localOnly)
                Toggle("Try Local Control (Experimental)", isOn: $ac.tryLocal)
                Toggle("Show AC Keys", isOn: $showACKeys)
                Button("Copy AC Key Code") {
                    if let code = ac.exportKeyCode() {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(code, forType: .string)
                    }
                }
                .disabled(ac.exportableDevices.isEmpty)
                Button(ac.unreadAlerts > 0 ? "AC Notifications (\(ac.unreadAlerts) new)…" : "AC Notifications…") {
                    showNotifications = true
                    UserDefaults.standard.set(HomeTab.climate.rawValue, forKey: "selectedTab")
                }
                Button("AC Diagnostics…") {
                    showDiagnostics = true
                    UserDefaults.standard.set(HomeTab.climate.rawValue, forKey: "selectedTab")
                }
                Divider()
                Button("Sign Out of MirAIe") { ac.logout() }
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

    private func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {}
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }
}

/// Segmented control drawn as a pill track. Tab switches are frequent, so selection changes instantly.
struct TabSwitcher: View {
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
                                .fill(selected ? Color(nsColor: .controlBackgroundColor) : .clear)
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
