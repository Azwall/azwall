import AzwallKit
import Foundation
import UIKit
import UniformTypeIdentifiers

/// Copies a secret to this device only (not to other devices through Universal Clipboard), for one minute.
func copySecret(_ text: String) {
    UIPasteboard.general.setItems([[UTType.plainText.identifier: text]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)])
}

/// `https://aztec.palmyr.io` → `aztec.palmyr.io`.
func siteOf(_ origin: String) -> String {
    origin.hasPrefix("https://") ? String(origin.dropFirst("https://".count)) : origin
}

/// `https://aztec.network/blog?x` → `aztec.network`.
func hostOf(_ url: String) -> String {
    let rest = url.range(of: "://").map { String(url[$0.upperBound...]) } ?? url
    return String(rest.prefix { $0 != "/" })
}

/// `0x1b17…3d37`.
func shortAddress(_ address: String, keep: Int = 4) -> String {
    guard address.count > 2 + keep * 2 + 1 else { return address }
    return "\(address.prefix(2 + keep))…\(address.suffix(keep))"
}

func activityTitle(_ item: ActivityItem) -> String {
    switch item.kind {
    case .deployAccount: "Account activated"
    case .sendPrivate: "Sent privately"
    case .sendPublic: "Sent publicly"
    case .shield: "Made private"
    case .unshield: "Made public"
    case .mint: "Minted"
    case .deployToken: "Token created" + (item.symbol.map { " (\($0))" } ?? "")
    case .dapp: item.summary ?? "App transaction"
    case .receive: "Received privately"
    case .claimFeeJuice: "Fee Juice claimed"
    }
}

func provingTitle(_ kind: ActivityKind) -> String {
    switch kind {
    case .deployAccount: "Activating your account"
    case .sendPrivate: "Sending privately"
    case .sendPublic: "Sending publicly"
    case .shield: "Making funds private"
    case .unshield: "Making funds public"
    case .mint: "Minting"
    case .deployToken: "Creating the token"
    case .dapp: "Proving the app's transaction"
    case .receive: "Receiving"
    case .claimFeeJuice: "Claiming your Fee Juice"
    }
}

/// "−1.5 TST", "+10 TST" or nil for transactions without an amount.
func activityAmount(_ item: ActivityItem) -> String? {
    guard let amount = item.amount else { return nil }
    let text = "\(Amounts.format(amount, decimals: item.decimals ?? 0)) \(item.symbol ?? "")".trimmingCharacters(in: .whitespaces)
    switch item.kind {
    case .sendPrivate, .sendPublic: return "−" + text
    case .mint, .receive, .claimFeeJuice: return "+" + text
    default: return text
    }
}

/// What Send and Shield say without any token: the same text on both apps.
let noTokens = "Add a token in the wallet first: Send and Shield move the tokens you follow."

/// The first line of an error, without the method prefix the bridge adds.
func friendlyError(_ message: String?) -> String { Errors.friendly(message) }
