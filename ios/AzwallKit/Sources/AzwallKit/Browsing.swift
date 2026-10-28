import Foundation

/// A block explorer of a network (networks.json): the start page lists it, a transaction's details link to it.
public struct KnownExplorer: Equatable, Identifiable, Sendable {
    public let name: String
    public let url: String
    /// A transaction's page, `{hash}` standing for its hash.
    public let txUrl: String

    public init(name: String, url: String, txUrl: String) {
        self.name = name
        self.url = url
        self.txUrl = txUrl
    }

    public var id: String { url }

    /// The page of the transaction `hash`; nil for anything but a 0x hex hash, which could otherwise reshape the URL.
    public func page(ofTx hash: String) -> String? {
        let digits = hash.dropFirst(2)
        guard hash.hasPrefix("0x"), !digits.isEmpty, digits.allSatisfy(\.isHexDigit) else { return nil }
        return txUrl.replacingOccurrences(of: "{hash}", with: hash)
    }

    /// The start page shows it as a tile, like the network's dApps.
    public var tile: KnownDapp { KnownDapp(name: name, url: url, description: "Blocks, transactions and contracts") }
}

/// A site the browser's start page suggests on a network (networks.json).
public struct KnownDapp: Equatable, Identifiable, Sendable {
    public let name: String
    public let url: String
    public let description: String

    public init(name: String, url: String, description: String = "") {
        self.name = name
        self.url = url
        self.description = description
    }

    public var id: String { url }

    /// What a tile shows under the name: `aztec.palmyr.io`.
    public var host: String {
        let rest = url.range(of: "://").map { String(url[$0.upperBound...]) } ?? url
        return String(rest.prefix { $0 != "/" })
    }

    /// The icon shipped with the app for this site (icons/dapps/<host>.png), drawn without asking the site.
    public var iconPath: String { "dapps/\(host).png" }

    /// Six hex digits derived from the URL (FNV-1a), for a badge colour that is the same on every device.
    public var tint: String {
        let hash = url.utf8.reduce(UInt32(0x811c_9dc5)) { ($0 ^ UInt32($1)) &* 0x0100_0193 }
        return String(format: "%06x", hash & 0xFF_FFFF)
    }
}

/// Where the address bar sends what is not an address: engines that do not profile who searches.
public enum SearchEngine: String, CaseIterable, Identifiable, Sendable {
    case duckduckgo = "DUCKDUCKGO"
    case brave = "BRAVE"
    case startpage = "STARTPAGE"

    public static let `default` = SearchEngine.duckduckgo

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .duckduckgo: "DuckDuckGo"
        case .brave: "Brave Search"
        case .startpage: "Startpage"
        }
    }

    private var query: String {
        switch self {
        case .duckduckgo: "https://duckduckgo.com/?q="
        case .brave: "https://search.brave.com/search?q="
        case .startpage: "https://www.startpage.com/do/search?q="
        }
    }

    /// The engine saved under `name`, or the default one.
    public static func named(_ name: String?) -> SearchEngine { name.flatMap(SearchEngine.init(rawValue:)) ?? .default }

    public func search(_ text: String) -> String {
        // Form encoding, as on Android: spaces become "+", everything but unreserved characters is escaped.
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._* ")
        let encoded = text.addingPercentEncoding(withAllowedCharacters: allowed)?.replacingOccurrences(of: " ", with: "+")
        return query + (encoded ?? "")
    }
}

public enum Browsing {
    /// The privacy policy (docs/privacy.md, published with the documentation): linked from the app, as stores require.
    public static let privacyPolicy = URL(string: "https://azwall.github.io/azwall/privacy")!

    /// Said when this build lacks the network's content script: sites open, but no dApp can reach the wallet.
    public static func noConnectionScript(network: String) -> String {
        "Apps cannot connect to the wallet on \(network): this version of Azwall lacks the connection script for it. Update the app."
    }

    /// What the address bar opens: a URL as typed, a host (https; http for this device), or a search with `engine`.
    /// Nil for nothing typed: the start page.
    public static func address(of input: String, engine: SearchEngine) -> String? {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return nil }
        if text.hasPrefix("https://") || text.hasPrefix("http://") { return text }
        if text.hasPrefix("localhost") || text.hasPrefix("127.0.0.1") { return "http://\(text)" }
        if !text.contains(" ") && text.contains(".") { return "https://\(text)" }
        return engine.search(text)
    }
}
