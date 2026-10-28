import Foundation

public enum ActivityKind: String, Codable, Sendable {
    case deployAccount = "DEPLOY_ACCOUNT", sendPrivate = "SEND_PRIVATE", sendPublic = "SEND_PUBLIC"
    case shield = "SHIELD", unshield = "UNSHIELD", mint = "MINT", deployToken = "DEPLOY_TOKEN"
    /// A transaction a dApp asked for; a private transfer received (read back from the chain).
    case dapp = "DAPP", receive = "RECEIVE"
    /// Fee Juice bridged from Ethereum, claimed by an active account (activation claims it otherwise).
    case claimFeeJuice = "CLAIM_FEE_JUICE"
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
    /// Tokens each account created on this network (account → token addresses): it is their minter.
    var created: [String: [String]]?
    /// What the home screen last showed per account, shown at the next start until the PXE catches up.
    var snapshots: [AccountSnapshot]?
    /// The chain all this belongs to (`WalletStore.chain(of:)`); nil before it was first recorded.
    var chain: String?
    /// Claim secrets the user entered for Fee Juice bridged to an account (account → hex secrets). Not custody
    /// secrets: a bridge deposit is bound to its recipient, so its secret only lets someone credit that recipient.
    var claimSecrets: [String: [String]]?
}

/// Non-secret data of one network: followed tokens and history. One JSON file per network, written atomically.
public final class WalletStore: @unchecked Sendable {
    public static let maxActivity = 200
    private let file: URL
    private let networkId: String
    private let lock = NSLock()
    private var data: StoredNetwork

    /// The file could not be read: the store started over, and the user is told (WalletSession.storeUnreadable).
    public let startedOver: Bool

    public init(directory: URL, networkId: String) {
        self.networkId = networkId
        file = directory.appendingPathComponent("wallet-\(networkId).json")
        (data, startedOver) = Self.read(file)
    }

    /// None yet is a first opening. One that cannot be read starts over too, after being moved aside: the next save
    /// would otherwise overwrite what it still holds, and someone must be able to tell why tokens and history are gone.
    private static func read(_ file: URL) -> (StoredNetwork, Bool) {
        guard FileManager.default.fileExists(atPath: file.path) else { return (StoredNetwork(), false) }
        do {
            return (try JSONDecoder().decode(StoredNetwork.self, from: Data(contentsOf: file)), false)
        } catch {
            DiagnosticLog.shared.report("store", error)
            let aside = file.appendingPathExtension("unreadable")
            // An older unreadable copy gives way to this one; one that cannot be moved is overwritten by the next save.
            try? FileManager.default.removeItem(at: aside)
            do { try FileManager.default.moveItem(at: file, to: aside) } catch { DiagnosticLog.shared.report("store", error) }
            return (StoredNetwork(), true)
        }
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

    public func snapshot(keyHash: String) -> AccountSnapshot? { lock.withLock { data.snapshots?.first { $0.keyHash == keyHash } } }

    public func saveSnapshot(_ snapshot: AccountSnapshot) {
        update { d in d.snapshots = (d.snapshots ?? []).filter { $0.keyHash != snapshot.keyHash } + [snapshot] }
    }

    public func created(account: String) -> [String] { lock.withLock { data.created?[account] ?? [] } }

    public func markCreated(account: String, token: String) {
        update { d in
            var all = d.created ?? [:]
            if !(all[account] ?? []).contains(token) { all[account, default: []].append(token) }
            d.created = all
        }
    }

    public func claimSecrets(account: String) -> [String] { lock.withLock { data.claimSecrets?[account] ?? [] } }

    public func addClaimSecret(account: String, secret: String) {
        guard !claimSecrets(account: account).contains(secret) else { return }
        update { d in d.claimSecrets = (d.claimSecrets ?? [:]).merging([account: (d.claimSecrets?[account] ?? []) + [secret]]) { $1 } }
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

    /// Items left proving by a crash: one without a hash cannot finish, its proof died with the process; one with a
    /// hash was being sent (WalletSession.followSubmission) and may have reached the node, so it is followed as pending.
    public func failInterrupted() {
        update { d in
            d.activity = d.activity.map { item in
                guard item.status == .proving else { return item }
                var next = item
                if item.txHash != nil {
                    next.status = .pending
                } else {
                    next.status = .failed
                    next.error = "interrupted before the proof was sent"
                }
                return next
            }
        }
    }

    /// The item with this id, if the history still has it.
    public func item(id: String) -> ActivityItem? {
        lock.withLock { data.activity.first { $0.id == id } }
    }

    /// Transactions being proven, of every account.
    public func proving() -> [ActivityItem] {
        lock.withLock { data.activity.filter { $0.status == .proving } }
    }

    /// A chain as the node reports it: its L1 and its rollup version, which a redeployment changes.
    public static func chain(of check: NetworkCheck) -> String { "\(check.l1ChainId):\(check.rollupVersion)" }

    /// The chain the network runs now. Another one than the data belongs to means the network was reset (a testnet
    /// redeployed under the same name): its tokens, contracts and history went with the old chain. The old data is
    /// kept aside in `wallet-<network>-<old chain>.json` and the store starts over, following the known tokens again.
    /// Returns true when it did.
    public func move(to chain: String, known: [TokenInfo]) -> Bool {
        let previous = lock.withLock { data.chain }
        if previous == chain { return false }
        guard let previous else {
            update { $0.chain = chain }
            return false
        }
        let aside = file.deletingLastPathComponent().appendingPathComponent("wallet-\(networkId)-\(previous.replacingOccurrences(of: ":", with: "-")).json")
        try? FileManager.default.removeItem(at: aside) // usually none: only a second reset from the same chain left one
        do { try FileManager.default.copyItem(at: file, to: aside) } catch { DiagnosticLog.shared.report("store", error) }
        update { $0 = StoredNetwork(chain: chain) }
        seed(known)
        return true
    }

    private func update(_ change: (inout StoredNetwork) -> Void) {
        lock.withLock {
            change(&data)
            // Kept in memory either way: the next change writes it again.
            do {
                try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
                try JSONEncoder().encode(data).write(to: file, options: .atomic)
            } catch {
                DiagnosticLog.shared.report("store", error)
            }
        }
    }
}
