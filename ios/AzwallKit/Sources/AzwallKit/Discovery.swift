import Foundation

/// Background work the wallet shows in its status line, like an IDE's.
public enum BackgroundTask: Equatable, Sendable {
    /// Syncing the PXE with the chain (it syncs while reading balances); `from` says how far behind it started.
    case syncing(from: SyncStatus?)
    /// Looking among the contracts dApps registered for tokens the account holds, and for its private history.
    case findingTokens
}

/// Tokens the account holds that the wallet does not follow (found among the contracts dApps registered in the
/// PXE), and the private transfers the PXE reads back from the chain.
extension WalletSession {
    /// Discovery simulates calls on every candidate: at most once per minute (the home screen refreshes more often).
    static let discoveryInterval: TimeInterval = 60

    /// Follows a found token; its balance is already known.
    public func followFound(_ token: FoundToken) {
        store.addToken(token.info)
        found.removeAll { $0 == token }
        tokens.append(TokenView(info: token.info, balance: token.balance))
        lastDiscovery = nil // the next refresh reads the new token's history
    }

    /// Stops suggesting a found token.
    public func dismissFound(_ token: FoundToken) {
        store.dismiss(token.address)
        found.removeAll { $0 == token }
    }

    /// A hint: a failure leaves the suggestions as they were.
    func discover(owner: String) async {
        if let last = lastDiscovery, Date().timeIntervalSince(last) < Self.discoveryInterval { return }
        guard let client = try? client else { return } // not open: the next refresh looks again
        lastDiscovery = Date()
        task = .findingTokens
        defer { task = nil }
        let known = store.tokens.map(\.address) + store.dismissed
        if let list = await DiagnosticLog.shared.attempt("discovery", { try await client.discoverTokens(owner: owner, known: known) }),
           selected == owner { found = list }
        await importHistory(owner: owner, client)
    }

    /// Adds the private transfers the PXE reads back from the chain that the history does not have.
    private func importHistory(owner: String, _ client: PxeClient) async {
        let followed = store.tokens
        guard !followed.isEmpty,
              let moves = await DiagnosticLog.shared.attempt("history", { try await client.tokenHistory(owner: owner, tokens: followed.map(\.address)) })
        else { return }
        store.addMissing(History.items(owner: owner, moves: moves, tokens: followed, known: store.activity(account: owner)))
        if selected == owner { activity = store.activity(account: owner) }
    }
}

/// The status line's text: what the wallet is doing in the background, or nil when idle. A PXE that is far behind
/// explains why balances are not there yet ("Syncing PXE · 1,240 blocks behind (36 h)").
public enum StatusText {
    public static func text(phase: SessionPhase, task: BackgroundTask?, activity: [ActivityItem], now: Date = Date()) -> String? {
        if phase == .starting { return "Starting the PXE…" }
        switch task {
        case .syncing(let from):
            guard let from, from.blocksBehind > 0 else { return "Syncing PXE…" }
            let blocks = from.blocksBehind == 1 ? "1 block" : "\(from.blocksBehind.formatted(.number.locale(Locale(identifier: "en_US")))) blocks"
            // A PXE that never synced has no block time to measure from.
            let since = from.syncedAt > 0 ? " (\(lag(now.timeIntervalSince1970 - Double(from.syncedAt))))" : ""
            return "Syncing PXE · \(blocks) behind\(since)"
        case .findingTokens: return "Reading your tokens and history…"
        case nil: break
        }
        let pending = activity.filter { $0.status == .pending }.count
        switch pending {
        case 0: return nil
        case 1: return "Waiting for 1 transaction to be mined…"
        default: return "Waiting for \(pending) transactions to be mined…"
        }
    }

    /// A sync lag, precise enough to explain a wait: "12 min", "36 h", "5 d".
    static func lag(_ seconds: Double) -> String {
        let minutes = Int(max(0, seconds) / 60)
        if minutes < 60 { return "\(minutes) min" }
        if minutes < 72 * 60 { return "\(minutes / 60) h" }
        return "\(minutes / (24 * 60)) d"
    }
}
