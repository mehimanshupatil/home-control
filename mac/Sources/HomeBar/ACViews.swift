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
    @AppStorage("showACKeys") private var showACKeys = false
    @AppStorage("showACDiagnostics") private var showDiagnostics = false

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

                if showACKeys {
                    ACKeysCard { showACKeys = false }
                }

                if showDiagnostics {
                    ACDiagnosticsCard { showDiagnostics = false }
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

struct ACCard: View {
    @EnvironmentObject private var ac: ACStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let device: ACDevice

    #if DEBUG
    nonisolated(unsafe) static var debugExpandMore = false
    @ViewState private var showMore = ACCard.debugExpandMore
    #else
    @ViewState private var showMore = false
    #endif

    private var state: ACState { device.state }
    private var tint: Color { ACModeStyle.tint(state.mode) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            temperatureRow
            ACInfoRow(device: device)
            controls
            if device.timerRange != nil {
                ACTimerRow(device: device)
            }
            if !(ac.profiles[device.id] ?? []).isEmpty {
                ACProfilesRow(device: device)
            }
            moreToggle
            if showMore {
                ACMoreControls(device: device, tint: tint)
                    .transition(.reveal(reduceMotion: reduceMotion))
            }
        }
        .card(tint: state.power ? tint : nil)
        .animation(.easeOut(duration: 0.15), value: state.power)
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name)
                    .font(.headline)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    if !device.space.isEmpty {
                        Text(device.space).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    TransportBadge(transport: device.transport, online: state.online)
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
                Text(state.power ? "Set to" : "Off")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(Self.format(device.tempRange.step >= 1 ? state.setTemp.rounded() : state.setTemp))
                    .font(.system(size: 40, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(state.power ? Color.primary : Color.secondary)
                    .accessibilityLabel("Target temperature \(Self.format(state.setTemp))")
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 8) {
                if let room = state.roomTemp {
                    Label("Room \(String(format: "%.0f°", room))", systemImage: "thermometer.medium")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
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
    }

    private var moreToggle: some View {
        Button { showMore.toggle() } label: {
            HStack(spacing: 6) {
                Text(showMore ? "Fewer controls" : "More controls")
                    .font(.caption.weight(.medium))
                Spacer()
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .semibold))
                    .rotationEffect(.degrees(showMore ? 180 : 0))
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 10)
            .frame(height: 28)
            .background(RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous).fill(Color.primary.opacity(0.04)))
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(showMore ? "Hide extra controls" : "Show extra controls")
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 8) {
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
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("AC Keys", systemImage: "key.fill").font(.subheadline.weight(.semibold))
                Spacer()
                Button("Hide", action: close).buttonStyle(.borderless).font(.caption)
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
