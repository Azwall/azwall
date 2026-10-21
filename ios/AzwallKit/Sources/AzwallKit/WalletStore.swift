import Foundation

public enum ActivityKind: String, Codable, Sendable {
    case deployAccount = "DEPLOY_ACCOUNT", sendPrivate = "SEND_PRIVATE", sendPublic = "SEND_PUBLIC"
    case shield = "SHIELD", unshield = "UNSHIELD", mint = "MINT", deployToken = "DEPLOY_TOKEN"
    /// A transaction a dApp asked for; a private transfer received (read back from the chain).
    case dapp = "DAPP", receive = "RECEIVE"
}

public enum ActivityStatus: String, Codable, Sendable {
    case proving = "PROVING", pending = "PENDING", confirmed = "CONFIRMED", failed = "FAILED"
}

/// One transaction made by this wallet. The PXE keeps no history, so the app does.
public struct ActivityItem: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let account: String
    public let kind: ActivityKind
    public var status: ActivityStatus
    public let createdAt: Date
    public var txHash: String?
    public var token: String?
    public var symbol: String?
    public var decimals: Int?
    /// Base units, as a decimal string.
    public var amount: String?
    public var counterparty: String?
    public var blockNumber: Int64?
    public var error: String?
    /// dApp items: the site that asked for the transaction, and what it does (its first call).
    public var origin: String?
    public var summary: String?

    public init(id: String = UUID().uuidString, account: String, kind: ActivityKind, status: ActivityStatus = .proving, createdAt: Date = Date()) {
        self.id = id
        self.account = account
        self.kind = kind
        self.status = status
        self.createdAt = createdAt
    }
}

private struct StoredNetwork: Codable {
    var tokens: [TokenInfo] = []
    var activity: [ActivityItem] = []
    /// Found tokens the user chose not to follow: not suggested again. Optional: older files lack it.
    var dismissed: [String]?
    /// The token each account shows at the top of the home screen (account → token address). Optional, like above.
    var primary: [String: String]?
    /// Known tokens already followed once: removing one keeps it removed. Optional, like above.
    var seeded: [String]?
}

/// Non-secret data of one network: followed tokens and history. One JSON file per network, written atomically.
public final class WalletStore: @unchecked Sendable {
    public static let maxActivity = 200
    private let file: URL
    private let lock = NSLock()
    private var data: StoredNetwork

    public init(directory: URL, networkId: String) {
        file = directory.appendingPathComponent("wallet-\(networkId).json")
        data = (try? JSONDecoder().decode(StoredNetwork.self, from: Data(contentsOf: file))) ?? StoredNetwork()
    }

    public var tokens: [TokenInfo] { lock.withLock { data.tokens } }

    public func activity(account: String) -> [ActivityItem] {
        lock.withLock { data.activity.filter { $0.account == account }.sorted { $0.createdAt > $1.createdAt } }
    }

    public func addToken(_ token: TokenInfo) {
        update { d in
            if !d.tokens.contains(where: { $0.address.lowercased() == token.address.lowercased() }) { d.tokens.append(token) }
        }
    }

    public func removeToken(_ address: String) {
        update { d in d.tokens.removeAll { $0.address.lowercased() == address.lowercased() } }
    }

    public var dismissed: [String] { lock.withLock { data.dismissed ?? [] } }

    /// Follows the network's known tokens not followed before (once each: a removed one stays removed).
    public func seed(_ known: [TokenInfo]) {
        update { d in
            let seeded = Set((d.seeded ?? []).map { $0.lowercased() })
            let fresh = known.filter { !seeded.contains($0.address.lowercased()) }
            guard !fresh.isEmpty else { return }
            let followed = Set(d.tokens.map { $0.address.lowercased() })
            d.tokens += fresh.filter { !followed.contains($0.address.lowercased()) }
            d.seeded = (d.seeded ?? []) + fresh.map(\.address)
        }
    }

    public func primary(account: String) -> String? { lock.withLock { data.primary?[account] } }

    public func setPrimary(account: String, token: String) {
        update { d in d.primary = (d.primary ?? [:]).merging([account: token]) { $1 } }
    }

    public func dismiss(_ address: String) {
        update { d in
            if !(d.dismissed ?? []).contains(address) { d.dismissed = (d.dismissed ?? []) + [address] }
        }
    }

    /// Sent transactions without a settled outcome: still pending, or failed without a receipt saying so.
    public func unsettled() -> [ActivityItem] {
        lock.withLock {
            data.activity.filter { item in
                guard item.txHash != nil else { return false }
                return item.status == .pending
                    || item.status == .failed && item.error != Settlement.reverted && item.error != Settlement.dropped
            }
        }
    }

    /// Adds the items the store does not have yet (by id), in one write.
    public func addMissing(_ items: [ActivityItem]) {
        update { d in
            let ids = Set(d.activity.map(\.id))
            d.activity += items.filter { !ids.contains($0.id) }
            d.activity.sort { $0.createdAt > $1.createdAt }
            d.activity = Array(d.activity.prefix(Self.maxActivity))
        }
    }

    /// Inserts or replaces the item with the same id; keeps the newest `maxActivity` items.
    public func upsert(_ item: ActivityItem) {
        update { d in
            d.activity.removeAll { $0.id == item.id }
            d.activity.append(item)
            d.activity.sort { $0.createdAt > $1.createdAt }
            d.activity = Array(d.activity.prefix(Self.maxActivity))
        }
    }

    /// Items left proving by a crash cannot finish: their proof died with the process.
    public func failInterrupted() {
        update { d in
            d.activity = d.activity.map { item in
                guard item.status == .proving else { return item }
                var failed = item
                failed.status = .failed
                failed.error = "interrupted before the proof was sent"
                return failed
            }
        }
    }

    private func update(_ change: (inout StoredNetwork) -> Void) {
        lock.withLock {
            change(&data)
            try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? JSONEncoder().encode(data).write(to: file, options: .atomic)
        }
    }
}
