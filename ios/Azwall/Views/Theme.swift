import AzwallKit
import SwiftUI
import UIKit

/// Same palette as the Android app: lavender accent, a color for private funds and one for public funds.
enum Palette {
    static let accent = Color(light: 0x5B3FD6, dark: 0xB9A4FF)
    static let privateFunds = Color(light: 0x5B3FD6, dark: 0xB9A4FF)
    static let publicFunds = Color(light: 0x55657D, dark: 0x8FA3BF)
    static let success = Color(light: 0x13855A, dark: 0x5BD6A2)
    static let pending = Color(light: 0xA86A00, dark: 0xF2C46D)
    static let danger = Color(light: 0xC62828, dark: 0xFF7B7B)
    static let background = Color(light: 0xF8F7FC, dark: 0x0D0B16)
    static let card = Color(light: 0xFFFFFF, dark: 0x17142A)
    static let chip = Color(light: 0xEEEBF6, dark: 0x1E1A2E)
}

extension Color {
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in UIColor(rgb: traits.userInterfaceStyle == .dark ? dark : light) })
    }
}

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}

extension Font {
    /// Addresses, hashes and keys.
    static let mono = Font.system(.footnote, design: .monospaced)
}

/// Deterministic avatar for an address: two hues derived from its bytes (like MetaMask's).
struct AccountAvatar: View {
    let address: String
    var size: CGFloat = 36

    var body: some View {
        let seed = address.replacingOccurrences(of: "0x", with: "")
        let a = Double(UInt64(seed.prefix(6), radix: 16) ?? 0)
        let b = Double(UInt64(seed.suffix(4), radix: 16) ?? 0)
        let h1 = a.truncatingRemainder(dividingBy: 360) / 360
        let h2 = (h1 + 0.25 + b.truncatingRemainder(dividingBy: 120) / 360).truncatingRemainder(dividingBy: 1)
        Circle()
            .fill(LinearGradient(colors: [Color(hue: h1, saturation: 0.55, brightness: 0.95), Color(hue: h2, saturation: 0.65, brightness: 0.75)],
                                 startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay(Circle().fill(.white.opacity(0.18)).frame(width: size * 0.64).offset(x: -size * 0.2, y: size * 0.22))
            .clipShape(Circle())
            .frame(width: size, height: size)
    }
}

/// Token badge: the symbol's first letters on a color derived from the token address.
struct TokenBadge: View {
    let symbol: String
    let address: String
    var size: CGFloat = 40

    var body: some View {
        let hue = Double(UInt64(address.suffix(6), radix: 16) ?? 0).truncatingRemainder(dividingBy: 360) / 360
        Text(symbol.prefix(3).uppercased())
            .font(.caption.bold())
            .foregroundStyle(Color(white: 0.1))
            .frame(width: size, height: size)
            .background(Circle().fill(Color(hue: hue, saturation: 0.45, brightness: 0.85)))
    }
}

struct StatusChip: View {
    let status: ActivityStatus

    var body: some View {
        let (label, color): (String, Color) = switch status {
        case .proving: ("Proving", Palette.privateFunds)
        case .pending: ("Pending", Palette.pending)
        case .confirmed: ("Confirmed", Palette.success)
        case .failed: ("Failed", Palette.danger)
        }
        Text(label).font(.caption2.weight(.semibold)).foregroundStyle(color)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Capsule().fill(color.opacity(0.14)))
    }
}

/// Round action under the balance (Send, Receive, Shield…).
struct CircleAction: View {
    let icon: String
    let label: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title3.weight(.semibold))
                    .frame(width: 52, height: 52)
                    .foregroundStyle(enabled ? Color.white : Color.secondary)
                    .background(Circle().fill(enabled ? Palette.accent : Palette.chip))
                Text(label).font(.caption.weight(.medium)).foregroundStyle(.primary)
            }
            .frame(width: 76)
        }
        .disabled(!enabled)
    }
}

/// A short address that copies the full one when tapped.
struct CopyAddress: View {
    let address: String

    var body: some View {
        Button {
            UIPasteboard.general.string = address
        } label: {
            HStack(spacing: 6) {
                Text(shortAddress(address)).font(.mono)
                Image(systemName: "doc.on.doc").font(.caption2)
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(Capsule().fill(Palette.chip))
        }
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.headline)
            .frame(maxWidth: .infinity, minHeight: 52)
            .foregroundStyle(enabled ? Color.white : Color.secondary)
            .background(Capsule().fill(enabled ? Palette.accent : Palette.chip))
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

struct Card<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 24).fill(Palette.card))
    }
}
