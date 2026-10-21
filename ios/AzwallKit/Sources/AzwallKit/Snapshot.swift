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

    static func keyHash(_ signingKey: String) -> String {
        SHA256.hash(data: Data(signingKey.lowercased().utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

extension WalletSession {
    /// Shows the last session's accounts and balances (stale) before the PXE is up; nothing on a first start.
    func restoreSnapshot() {
        var snaps: [AccountSnapshot] = []
        for secrets in vaultAccounts {
            guard let snap = store.snapshot(keyHash: AccountSnapshot.keyHash(secrets.signingKey)) else { return }
            snaps.append(snap)
        }
        guard let first = snaps.first else { return }
        accounts = zip(vaultAccounts, snaps).map { AccountView(alias: $0.alias, address: $1.address, signingKey: $0.signingKey, deployed: $1.deployed) }
        selected = first.address
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
            balances: tokens.compactMap(\.balance),
        ))
    }
}
