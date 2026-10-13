import SwiftUI

enum ACModeStyle {
    static func symbol(_ mode: String) -> String {
        switch mode {
        case "cool": "snowflake"
        case "heat": "sun.max"
        case "dry": "drop"
        case "fan": "fan"
        default: "arrow.triangle.2.circlepath"
        }
    }

    static func tint(_ mode: String) -> Color {
        switch mode {
        case "cool": .blue
        case "heat": .orange
        case "dry": .teal
        case "fan": .mint
        default: .purple
        }
    }

    static func fanTitle(_ speed: String) -> String {
        speed == "medium" ? "Med" : speed.capitalized
    }
}

struct ACSection: View {
    @EnvironmentObject private var ac: ACStore

    var body: some View {
        if !ac.loggedIn {
            LoginView()
        } else {
            VStack(alignment: .leading, spacing: Metrics.sectionSpacing) {
                SectionHeader(
                    title: "Air Conditioners",
                    detail: ac.localOnly ? "Local network only" : (ac.mqttConnected ? "Cloud connected" : nil),
                    busy: ac.loading,
                    refreshHelp: ac.localOnly ? "Refresh devices and LAN keys" : "Reload devices"
                ) { Task { await ac.refresh() } }

                if let error = ac.errorMessage {
                    ErrorBanner(message: error)
                }

                if ac.devices.isEmpty && !ac.loading {
                    EmptyStateView(
                        symbol: "air.conditioner.horizontal",
                        title: "No air conditioners",
                        message: "This MirAIe account has no ACs yet. Add one in the MirAIe app, then reload.",
                        actionTitle: "Reload"
                    ) { Task { await ac.reload() } }
                }

                ForEach(ac.devices) { device in
                    ACCard(device: device)
                }
            }
        }
    }
}

struct SectionHeader: View {
    let title: String
    var detail: String?
    var busy = false
    var refreshHelp: String
    let refresh: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(title).font(.subheadline.weight(.semibold))
            if let detail {
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if busy {
                ProgressView().controlSize(.small)
            } else {
                RoundIconButton(symbol: "arrow.clockwise", size: 24, help: refreshHelp, action: refresh)
            }
        }
        .frame(minHeight: 24)
    }
}

struct ErrorBanner: View {
    let message: String

    var body: some View {
        Label(message, systemImage: "exclamationmark.triangle.fill")
            .font(.caption)
            .foregroundStyle(.red)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.red.opacity(0.1)))
    }
}

/// Compact AC card (docs/UI.md): header, temperature, mode, fan, active-state chips, profiles, action bar.
struct ACCard: View {
    @EnvironmentObject private var ac: ACStore
    let device: ACDevice

    private var state: ACState { device.state }
    private var tint: Color { ACModeStyle.tint(state.mode) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            temperatureRow
            modeRow
            fanRow
            ACStatusChips(device: device)
            if !(ac.profiles[device.id] ?? []).isEmpty {
                ACProfilesRow(device: device)
            }
            ACActionBar(device: device)
        }
        .card(tint: state.power ? tint : nil)
        .animation(.easeOut(duration: 0.15), value: state.power)
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(device.name)
                    .font(.headline)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    if !device.space.isEmpty {
                        Text(device.space).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    TransportBadge(transport: device.transport, online: state.online)
                    if let rssi = state.raw["rssi"]?.doubleValue, rssi < 0 {
                        Image(systemName: "wifi", variableValue: Self.signal(rssi))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .help(String(format: "Wi-Fi signal %.0f dBm", rssi))
                            .accessibilityLabel(String(format: "Wi-Fi signal %.0f dBm", rssi))
                    }
                }
            }
            Spacer(minLength: 8)
            PowerButton(isOn: state.power, tint: tint, label: "\(device.name) power") {
                ac.setPower(device, !state.power)
            }
        }
    }

    private var temperatureRow: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 0) {
                Text(Self.format(device.tempRange.step >= 1 ? state.setTemp.rounded() : state.setTemp))
                    .font(.system(size: 44, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(state.power ? Color.primary : Color.secondary)
                    .accessibilityLabel("Target temperature \(Self.format(state.setTemp))")
                Text(caption)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .lineLimit(1)
            }
            Spacer()
            HStack(spacing: 8) {
                let range = device.tempRange
                RoundIconButton(symbol: "minus", size: 32, help: "Decrease temperature") {
                    ac.setTemp(device, state.setTemp - range.step)
                }
                .disabled(state.setTemp <= range.min)
                RoundIconButton(symbol: "plus", size: 32, help: "Increase temperature") {
                    ac.setTemp(device, state.setTemp + range.step)
                }
                .disabled(state.setTemp >= range.max)
            }
        }
    }

    /// "Off · Room 29° · 62% humidity · 840 W" — only the parts the AC reports.
    private var caption: String {
        var parts: [String] = []
        if !state.power { parts.append("Off") }
        if let room = state.roomTemp { parts.append(String(format: "Room %.0f°", room)) }
        if let cap = device.capability("RoomHumidity"), cap.key != cap.name, let h = state.raw[cap.key]?.doubleValue {
            parts.append(String(format: "%.0f%% humidity", h))
        }
        if let cap = device.capability("CurrentPowerConsumption"), cap.key != cap.name, let w = state.raw[cap.key]?.doubleValue {
            parts.append(String(format: "%.0f W", w))
        }
        return parts.isEmpty ? (state.power ? "Set temperature" : "Off") : parts.joined(separator: " · ")
    }

    private var modeRow: some View {
        HStack(spacing: 4) {
            ForEach(device.modeOptions, id: \.self) { mode in
                OptionTile(
                    title: mode.capitalized,
                    symbol: ACModeStyle.symbol(mode),
                    selected: state.mode == mode,
                    tint: ACModeStyle.tint(mode),
                    muted: !state.power
                ) { ac.setMode(device, mode) }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Mode")
    }

    private var fanRow: some View {
        HStack(spacing: 6) {
            Image(systemName: "fan")
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(width: 16)
                .accessibilityHidden(true)
            HStack(spacing: 4) {
                ForEach(device.fanOptions, id: \.self) { speed in
                    OptionTile(
                        title: ACModeStyle.fanTitle(speed),
                        symbol: "",
                        selected: state.fan == speed,
                        tint: tint,
                        compact: true,
                        muted: !state.power
                    ) { ac.setFan(device, speed) }
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Fan speed")
    }

    static func signal(_ rssi: Double) -> Double {
        rssi >= -55 ? 1 : rssi >= -67 ? 0.67 : rssi >= -80 ? 0.34 : 0
    }

    static func format(_ t: Double) -> String {
        t == t.rounded() ? String(format: "%.0f°", t) : String(format: "%.1f°", t)
    }
}

struct TransportBadge: View {
    let transport: ACTransport
    var online: Bool?

    var body: some View {
        Group {
            if online == false {
                StatusPill(text: "Offline", color: .red, symbol: "wifi.slash")
            } else {
                switch transport {
                case .local: StatusPill(text: "Local", color: .green, symbol: "wifi")
                case .cloud: StatusPill(text: "Cloud", color: .blue, symbol: "cloud.fill")
                case .connecting: StatusPill(text: "Connecting", color: .secondary)
                case .unavailable: StatusPill(text: "Unreachable", color: .red, symbol: "exclamationmark")
                }
            }
        }
        .help(transport == .local ? "Controlled over your Wi-Fi (UDP 5001)"
              : transport == .cloud ? "Controlled through the MirAIe cloud" : "")
    }
}

struct LoginView: View {
    @EnvironmentObject private var ac: ACStore
    @ViewState private var login = ""
    @ViewState private var password = ""

    var body: some View {
        VStack(spacing: 14) {
            VStack(spacing: 6) {
                Image(systemName: "air.conditioner.horizontal")
                    .font(.system(size: 30, weight: .light))
                    .foregroundStyle(Color.accentColor)
                Text("Connect your Panasonic AC").font(.headline)
                Text("Sign in with your MirAIe account once. After that, your AC is controlled over your Wi-Fi.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.top, 8)

            VStack(spacing: 8) {
                TextField("Mobile number or email", text: $login)
                    .textFieldStyle(.roundedBorder)
                    .textContentType(.username)
                SecureField("Password", text: $password)
                    .textFieldStyle(.roundedBorder)
                    .textContentType(.password)
                    .onSubmit(submit)
            }
            .controlSize(.large)

            if let error = ac.errorMessage {
                ErrorBanner(message: error)
            }

            Button(action: submit) {
                HStack(spacing: 6) {
                    if ac.loading { ProgressView().controlSize(.small) }
                    Text(ac.loading ? "Signing in…" : "Sign In")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .keyboardShortcut(.defaultAction)
            .disabled(login.isEmpty || password.isEmpty || ac.loading)

            KeyCodeImport()

            Text("10-digit mobile numbers are sent as +91. Your password stays in the macOS Keychain.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .card()
    }

    private func submit() {
        guard !login.isEmpty, !password.isEmpty, !ac.loading else { return }
        Task { await ac.login(login, password: password) }
    }
}

/// Shows each AC's LAN key so it can be backed up and restored without signing in.
struct ACKeysCard: View {
    @EnvironmentObject private var ac: ACStore
    @ViewState private var copied = false
    /// Shown with a Hide button when embedded; nil on its own page.
    var close: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let close {
                HStack {
                    Label("AC Keys", systemImage: "key.fill").font(.subheadline.weight(.semibold))
                    Spacer()
                    Button("Hide", action: close).buttonStyle(.borderless).font(.caption)
                }
            }
            Text("These keys control your ACs over Wi-Fi with no Panasonic sign-in. Anyone on your network who has them can control your ACs, so keep them private.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            if ac.exportableDevices.isEmpty {
                Text("No keys yet. Your AC model may not support local control, or keys haven't been fetched. Try Refresh.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            ForEach(ac.exportableDevices, id: \.device.id) { item in
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.device.name).font(.caption.weight(.semibold))
                    keyRow("MAC", item.info.mac)
                    keyRow("IP", item.info.ip ?? "unknown")
                    keyRow("Key", item.info.key)
                    keyRow("IV", item.info.iv)
                }
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous).fill(Color.primary.opacity(0.05)))
            }

            if !ac.exportableDevices.isEmpty {
                Button {
                    guard let code = ac.exportKeyCode() else { return }
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(code, forType: .string)
                    copied = true
                    Task { try? await Task.sleep(for: .seconds(2)); copied = false }
                } label: {
                    Label(copied ? "Copied" : "Copy Key Code", systemImage: copied ? "checkmark" : "doc.on.doc")
                        .contentTransition(.symbolEffect(.replace))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                Text("Paste the code into “Use a key code” on the sign-in screen, on this Mac or another one.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .card()
    }

    private func keyRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(width: 28, alignment: .leading)
            Text(value)
                .font(.system(.caption2, design: .monospaced))
                .textSelection(.enabled)
                .lineLimit(2)
                .truncationMode(.middle)
        }
    }
}

/// Sign-in alternative: paste a previously copied key code to go local-only.
struct KeyCodeImport: View {
    @EnvironmentObject private var ac: ACStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ViewState private var open = false
    @ViewState private var code = ""

    var body: some View {
        VStack(spacing: 8) {
            Button(open ? "Cancel" : "Use a key code instead") { open.toggle() }
                .buttonStyle(.borderless)
                .font(.caption)
            if open {
                VStack(spacing: 8) {
                    TextField("miraie-local-v1:…", text: $code)
                        .textFieldStyle(.roundedBorder)
                        .font(.system(.caption, design: .monospaced))
                        .onSubmit(apply)
                    HStack {
                        Button("Paste") { code = NSPasteboard.general.string(forType: .string) ?? code }
                        Spacer()
                        Button("Use Key Code", action: apply)
                            .disabled(code.isEmpty)
                    }
                    .controlSize(.small)
                }
                .transition(.reveal(reduceMotion: reduceMotion))
            }
        }
    }

    private func apply() {
        guard !code.isEmpty else { return }
        ac.importKeyCode(code)
    }
}
