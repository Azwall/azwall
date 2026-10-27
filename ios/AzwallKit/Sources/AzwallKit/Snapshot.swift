import CryptoKit
import Foundation

/// What the home screen last showed for an account, saved after each successful refresh: at the next start the
/// wallet shows it at once, marked stale, while the PXE starts and syncs (that takes seconds, the address itself
/// comes from the PXE). Keyed by a hash of the signing key, never the key: nothing here is secret.
public struct AccountSnapshot: Codable, Equatable, Sendable {
    public let keyHash: String
    public let address: String
    public let deployed: Bool?
    public var balances: [TokenBalance] = []
    public var at: Date = Date()
    /// Base units of Fee Juice, the top of the home screen.
    public var feeJuice: String?

    static func keyHash(_ signingKey: String) -> String {
        SHA256.hash(data: Data(signingKey.lowercased().utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

extension WalletSession {
    /// Shows the last session's accounts and balances (stale) before the PXE is up: the accounts known from a previous
    /// start (one without a snapshot shows once the PXE gives its address); nothing on a first start on this network.
    func restoreSnapshot() {
        let known = vaultAccounts.compactMap { secrets in
            store.snapshot(keyHash: AccountSnapshot.keyHash(secrets.signingKey)).map { (secrets, $0) }
        }
        guard let first = known.first?.1 else { return }
        accounts = known.map { AccountView(alias: $0.0.alias, address: $0.1.address, signingKey: $0.0.signingKey, deployed: $0.1.deployed) }
        selected = first.address
        feeJuice = first.feeJuice
        tokens = store.tokens.map { info in TokenView(info: info, balance: first.balances.first { $0.token.lowercased() == info.address.lowercased() }) }
        activity = store.activity(account: first.address)
        primary = store.primary(account: first.address)
        created = store.created(account: first.address)
        stale = true
    }

    /// Saves what a successful refresh of the selected account showed.
    func saveSnapshot() {
        guard let account else { return }
        store.saveSnapshot(AccountSnapshot(
            keyHash: AccountSnapshot.keyHash(account.signingKey), address: account.address, deployed: account.deployed,
            balances: tokens.compactMap(\.balance), feeJuice: feeJuice,
        ))
    }

    /// Updates only the Fee Juice of an account's snapshot: read after its balances, it must not erase them.
    func saveFeeJuice(_ account: AccountView, _ units: String) {
        guard var snap = store.snapshot(keyHash: AccountSnapshot.keyHash(account.signingKey)) else { return }
        snap.feeJuice = units
        store.saveSnapshot(snap)
    }

    /// Keeps each registered account's address for the next start, even one never refreshed (no balances yet).
    func rememberAccounts(_ views: [AccountView]) {
        for view in views where store.snapshot(keyHash: AccountSnapshot.keyHash(view.signingKey)) == nil {
            store.saveSnapshot(AccountSnapshot(keyHash: AccountSnapshot.keyHash(view.signingKey), address: view.address, deployed: view.deployed))
        }
    }
}
