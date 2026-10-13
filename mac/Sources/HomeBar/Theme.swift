import SwiftUI

/// Shared surface metrics. Cards use concentric radii: card radius = tile radius + card padding.
enum Metrics {
    static let cardPadding: CGFloat = 12
    static let tileRadius: CGFloat = 6
    static let cardRadius: CGFloat = tileRadius + cardPadding // 18
    static let sectionSpacing: CGFloat = 12
}

/// Elevated card surface: fill plus layered soft shadows instead of a depth border.
struct CardSurface: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    var tint: Color? = nil

    func body(content: Content) -> some View {
        content
            .padding(Metrics.cardPadding)
            .background {
                RoundedRectangle(cornerRadius: Metrics.cardRadius, style: .continuous)
                    .fill(.background.secondary)
                    // Dark mode: lift the card off the window so elevation reads without a border.
                    .overlay {
                        if colorScheme == .dark {
                            RoundedRectangle(cornerRadius: Metrics.cardRadius, style: .continuous).fill(Color.white.opacity(0.04))
                        }
                    }
                    .overlay {
                        if let tint {
                            RoundedRectangle(cornerRadius: Metrics.cardRadius, style: .continuous)
                                .fill(tint.opacity(0.08))
                        }
                    }
                    .shadow(color: .black.opacity(0.06), radius: 0.5, y: 0.5)
                    .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
            }
    }
}

extension View {
    func card(tint: Color? = nil) -> some View { modifier(CardSurface(tint: tint)) }
}

/// Press feedback: scale to 0.96 over 150 ms ease-out. Disabled buttons and Reduce Motion skip the scale.
struct PressableStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        let scales = isEnabled && !reduceMotion
        configuration.label
            .contentShape(Rectangle())
            .scaleEffect(scales && configuration.isPressed ? 0.96 : 1)
            .opacity(!scales && configuration.isPressed ? 0.7 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == PressableStyle {
    static var pressable: PressableStyle { PressableStyle() }
}

/// Selectable tile used for AC mode and fan speed. Selection is instant (frequent interaction).
struct OptionTile: View {
    let title: String
    let symbol: String
    let selected: Bool
    var tint: Color = .accentColor
    var compact = false
    /// Device is off: show the selection with a neutral fill instead of the tint.
    var muted = false
    let action: () -> Void

    private var selectedFill: AnyShapeStyle {
        muted ? AnyShapeStyle(Color.primary.opacity(0.16)) : AnyShapeStyle(tint)
    }

    var body: some View {
        Button(action: action) {
            VStack(spacing: compact ? 2 : 4) {
                if !compact {
                    Image(systemName: symbol)
                        .font(.system(size: 14, weight: selected ? .semibold : .regular))
                        .frame(height: 16)
                }
                Text(title)
                    .font(.caption2.weight(selected ? .semibold : .regular))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, minHeight: compact ? 24 : 44)
            .foregroundStyle(selected && !muted ? Color.white : Color.primary.opacity(selected ? 1 : 0.7))
            .background(
                RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous)
                    .fill(selected ? selectedFill : AnyShapeStyle(Color.primary.opacity(0.06)))
            )
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Circular icon button (steppers, refresh).
struct RoundIconButton: View {
    let symbol: String
    var size: CGFloat = 28
    var help: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size * 0.42, weight: .semibold))
                .frame(width: size, height: size)
                .background(Circle().fill(Color.primary.opacity(0.08)))
        }
        .buttonStyle(.pressable)
        .help(help)
        .accessibilityLabel(help)
    }
}

/// Large circular power toggle. Icon and fill change together, so state never relies on motion alone.
struct PowerButton: View {
    let isOn: Bool
    var tint: Color = .accentColor
    var label = "Power"
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "power")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(isOn ? Color.white : Color.secondary)
                .frame(width: 34, height: 34)
                .background(Circle().fill(isOn ? AnyShapeStyle(tint) : AnyShapeStyle(Color.primary.opacity(0.08))))
                .animation(.easeOut(duration: 0.15), value: isOn)
        }
        .buttonStyle(.pressable)
        .help(isOn ? "Turn off" : "Turn on")
        .accessibilityLabel(label)
        .accessibilityValue(isOn ? "On" : "Off")
    }
}

/// Small status capsule.
struct StatusPill: View {
    @Environment(\.colorScheme) private var colorScheme
    let text: String
    let color: Color
    var symbol: String? = nil

    /// Pastel tints fail contrast on light pills; blend toward black in light mode.
    private var textColor: Color {
        guard colorScheme == .light, let dark = NSColor(color).blended(withFraction: 0.4, of: .black) else { return color }
        return Color(nsColor: dark)
    }

    var body: some View {
        HStack(spacing: 3) {
            if let symbol { Image(systemName: symbol).font(.system(size: 8, weight: .bold)) }
            Text(text)
        }
        .font(.caption2.weight(.semibold))
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .foregroundStyle(textColor)
        .background(Capsule().fill(color.opacity(0.15)))
    }
}

/// Card-level status chip (active timer, running sleep profile, alerts). Larger than `StatusPill`, with an
/// optional close button inside the capsule. Text never wraps.
struct StatusChip: View {
    @Environment(\.colorScheme) private var colorScheme
    let text: String
    let color: Color
    let symbol: String
    var help: String?
    var closeLabel: String?
    var onClose: (() -> Void)?

    private var textColor: Color {
        guard colorScheme == .light, let dark = NSColor(color).blended(withFraction: 0.4, of: .black) else { return color }
        return Color(nsColor: dark)
    }

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: symbol).font(.system(size: 10, weight: .semibold))
            Text(text).lineLimit(1).monospacedDigit()
            if let onClose {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 8, weight: .bold))
                        .frame(width: 16, height: 16)
                        .background(Circle().fill(color.opacity(0.18)))
                }
                .buttonStyle(.pressable)
                .help(closeLabel ?? "Cancel")
                .accessibilityLabel(closeLabel ?? "Cancel")
            }
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(textColor)
        .padding(.leading, 8)
        .padding(.trailing, onClose == nil ? 8 : 4)
        .frame(height: 24)
        .background(Capsule().fill(color.opacity(0.14)))
        .help(help ?? text)
        .fixedSize()
    }
}

/// Centered empty / info state.
struct EmptyStateView: View {
    let symbol: String
    let title: String
    let message: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: symbol)
                .font(.system(size: 28, weight: .light))
                .foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .buttonStyle(.pressable)
                    .font(.callout.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(Color.accentColor.opacity(0.15)))
                    .foregroundStyle(Color.accentColor)
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
    }
}

/// Expand/collapse transition: enter 300 ms with 12 pt rise, exit 150 ms; opacity-only under Reduce Motion.
extension AnyTransition {
    static func reveal(reduceMotion: Bool) -> AnyTransition {
        if reduceMotion { return .opacity.animation(.easeOut(duration: 0.2)) }
        return .asymmetric(
            insertion: .opacity.combined(with: .offset(y: 12)).animation(.easeOut(duration: 0.3)),
            removal: .opacity.combined(with: .offset(y: -12)).animation(.easeOut(duration: 0.15))
        )
    }
}
