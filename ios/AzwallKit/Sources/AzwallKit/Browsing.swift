import Foundation

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
