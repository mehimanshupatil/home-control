import SwiftUI

/// Detail pages opened from an AC card's action bar (docs/UI.md).
enum ACPage: String, CaseIterable, Identifiable, Hashable {
    case timer, sleep, air, energy, more
    var id: String { rawValue }

    var title: String {
        switch self {
        case .timer: "Timer"
        case .sleep: "Sleep"
        case .air: "Air"
        case .energy: "Energy"
        case .more: "More"
        }
    }

    var symbol: String {
        switch self {
        case .timer: "timer"
        case .sleep: "moon.zzz"
        case .air: "wind"
        case .energy: "bolt"
        case .more: "ellipsis"
        }
    }

    /// Needs the MirAIe cloud.
    var cloud: Bool { self == .sleep || self == .energy }
}

/// Everything the panel can push on top of the tab list.
enum Route: Hashable {
    case ac(String, ACPage)
    case bulb(String)
    case notifications, diagnostics, keys, rooms
}

/// Single-level navigation: one page at a time over the tab list, which stays alive underneath
/// so its scroll position survives Back.
@MainActor
final class Navigator: ObservableObject {
    @Published var route: Route?

    init(route: Route? = nil) { self.route = route }

    func push(_ r: Route) { route = r }
    func pop() { route = nil }
}

extension AnyTransition {
    /// Page push: opacity + 12 pt from the trailing edge, 300 ms in / 150 ms out; opacity only under Reduce Motion.
    static func page(reduceMotion: Bool) -> AnyTransition {
        if reduceMotion {
            return .asymmetric(insertion: .opacity.animation(.easeOut(duration: 0.3)),
                               removal: .opacity.animation(.easeOut(duration: 0.15)))
        }
        return .asymmetric(
            insertion: .opacity.combined(with: .offset(x: 12)).animation(.easeOut(duration: 0.3)),
            removal: .opacity.combined(with: .offset(x: 12)).animation(.easeOut(duration: 0.15))
        )
    }
}

/// Back bar for detail pages: `‹ Back` capsule, then a large title with an optional subtitle.
struct PageBar: View {
    @EnvironmentObject private var nav: Navigator
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { nav.pop() } label: {
                HStack(spacing: 3) {
                    Image(systemName: "chevron.left").font(.system(size: 11, weight: .semibold))
                    Text("Back").font(.caption.weight(.semibold))
                }
                .padding(.leading, 8)
                .padding(.trailing, 10) // 2 pt less on the icon side (optical)
                .frame(height: 24)
                .background(Capsule().fill(Color.primary.opacity(0.08)))
            }
            .buttonStyle(.pressable)
            .help("Back")
            .accessibilityLabel("Back")

            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.title3.weight(.semibold))
                if let subtitle {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Small grouped-section card used on detail pages: caption title, optional trailing accessory, content.
struct PageSection<Content: View, Accessory: View>: View {
    let title: String?
    @ViewBuilder let accessory: () -> Accessory
    @ViewBuilder let content: () -> Content

    init(_ title: String?, @ViewBuilder accessory: @escaping () -> Accessory, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.accessory = accessory
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title {
                HStack(spacing: 6) {
                    Text(title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Spacer()
                    accessory()
                }
            }
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }
}

extension PageSection where Accessory == EmptyView {
    init(_ title: String?, @ViewBuilder content: @escaping () -> Content) {
        self.init(title, accessory: { EmptyView() }, content: content)
    }
}

/// "MirAIe cloud" caption for page sections.
struct CloudCaption: View {
    var body: some View {
        Label("MirAIe cloud", systemImage: "cloud")
            .font(.caption2)
            .foregroundStyle(.secondary)
            .help("This feature uses Panasonic's servers")
    }
}

/// A row that pushes a page: icon, title, optional badge, chevron.
struct NavRow: View {
    let title: String
    let symbol: String
    var badge: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .font(.system(size: 13, weight: .regular))
                    .foregroundStyle(.secondary)
                    .frame(width: 18)
                Text(title).font(.callout)
                Spacer()
                if let badge {
                    Text(badge)
                        .font(.caption2.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(.white)
                        .padding(.horizontal, 6)
                        .frame(height: 16)
                        .background(Capsule().fill(Color.red))
                }
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(.tertiary)
            }
            .frame(height: 32)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressable)
    }
}
