import AzwallKit
import Foundation

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

/// A 32-byte hex key as four lines of four 4-character groups, easy to copy by hand.
func groupKey(_ key: String) -> String {
    let body = Array(key.hasPrefix("0x") ? String(key.dropFirst(2)) : key)
    let groups = stride(from: 0, to: body.count, by: 4).map { String(body[$0..<min($0 + 4, body.count)]) }
    return stride(from: 0, to: groups.count, by: 4).map { groups[$0..<min($0 + 4, groups.count)].joined(separator: " ") }.joined(separator: "\n")
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
    }
}

/// "−1.5 TST", "+10 TST" or nil for transactions without an amount.
func activityAmount(_ item: ActivityItem) -> String? {
    guard let amount = item.amount else { return nil }
    let text = "\(Amounts.format(amount, decimals: item.decimals ?? 0)) \(item.symbol ?? "")".trimmingCharacters(in: .whitespaces)
    switch item.kind {
    case .sendPrivate, .sendPublic: return "−" + text
    case .mint, .receive: return "+" + text
    default: return text
    }
}

/// The first line of an error, without the method prefix the bridge adds.
func friendlyError(_ message: String?) -> String {
    let line = message?.split(separator: "\n").first.map(String.init) ?? ""
    let trimmed = line.split(separator: ":", maxSplits: 1).last.map { $0.trimmingCharacters(in: .whitespaces) } ?? line
    return trimmed.isEmpty ? "Something went wrong" : trimmed
}
