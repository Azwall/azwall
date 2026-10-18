import Foundation
import Observation

/// A running PXE host for one network.
public protocol RunningPxe: Sendable {
    var client: PxeClient { get }
    func stop() async
}

/// Starts the PXE host bundle of a network (a hidden WKWebView in the app).
public protocol PxeLauncher: Sendable {
    func launch(_ network: NetworkInfo) async throws -> RunningPxe
}

public struct AccountView: Equatable, Identifiable, Sendable {
    public var alias: String
    public let address: String
    public let signingKey: String
    public var deployed: Bool?
    public var id: String { address }
}

public struct TokenView: Equatable, Identifiable, Sendable {
    public let info: TokenInfo
    public var balance: TokenBalance?
    public var id: String { info.address }

    /// Private + public, in base units.
    public var total: String? { balance.map { Amounts.add($0.private, $0.public) } }
}

public enum SessionPhase: Equatable, Sendable { case starting, ready, failed }

/// One unlocked wallet on one network: the PXE host, the vault's accounts (whose address depends on the
/// network's Aztec version), followed tokens with balances, and the transaction history.
@MainActor
@Observable
public final class WalletSession {
    public let network: NetworkInfo
    public private(set) var phase: SessionPhase = .starting
    public private(set) var check: NetworkCheck?
    public private(set) var accounts: [AccountView] = []
    public private(set) var selected: String?
    public private(set) var tokens: [TokenView] = []
    public private(set) var activity: [ActivityItem] = []
    public private(set) var refreshing = false
    public private(set) var error: String?
    public private(set) var proving: ActivityItem?
    public private(set) var provingSince: Date?

    public var account: AccountView? { accounts.first { $0.address == selected } }

    private let backend: WalletBackend
    private let launcher: PxeLauncher
    private let vault: Vault
    private var contents: VaultContents
    let store: WalletStore
    private var pxe: RunningPxe?

    public init(backend: WalletBackend, launcher: PxeLauncher, vault: Vault, contents: VaultContents, storeDirectory: URL, network: NetworkInfo) {
        self.backend = backend
        self.launcher = launcher
        self.vault = vault
        self.contents = contents
        self.network = network
        store = WalletStore(directory: storeDirectory, networkId: network.id)
    }

    var client: PxeClient {
        get throws {
            guard let pxe else { throw PxeError("the wallet is not open") }
            return pxe.client
        }
    }

    /// Starts the PXE, opens the network and registers every account. Failures land in `error`.
    public func open() async {
        store.failInterrupted()
        let backend = backend, id = network.id
        Task.detached { try? backend.prepareProver(networkId: id) }
        do {
            let running = try await launcher.launch(network)
            pxe = running
            let params = try await Task.detached { try backend.pxeOpenParams(networkId: id) }.value
            check = try await running.client.open(networkParamsJSON: params, dbKey: contents.dbKey)
            var views: [AccountView] = []
            for secrets in contents.accounts { views.append(try await register(secrets)) }
            accounts = views
            selected = selected ?? views.first?.address
            phase = .ready
            await refresh()
        } catch {
            phase = .failed
            self.error = error.localizedDescription
        }
    }

    public func close() async {
        guard let running = pxe else { return }
        pxe = nil
        try? await running.client.close()
        await running.stop()
    }

    public func select(_ address: String) async {
        guard accounts.contains(where: { $0.address == address }) else { return }
        selected = address
        tokens = tokens.map { TokenView(info: $0.info, balance: nil) }
        await refresh()
    }

    /// Re-reads deployment status, balances and history of the selected account.
    public func refresh() async {
        guard let account, !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        do {
            let client = try self.client
            let deployed = try await client.isDeployed(account.address)
            let followed = store.tokens
            let balances = deployed && !followed.isEmpty ? try await client.balances(owner: account.address, tokens: followed.map(\.address)) : []
            if let i = accounts.firstIndex(where: { $0.address == account.address }) { accounts[i].deployed = deployed }
            tokens = followed.map { info in TokenView(info: info, balance: balances.first { $0.token.lowercased() == info.address.lowercased() }) }
            activity = store.activity(account: account.address)
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Creates a fresh account, or imports one from its key, and saves it in the vault.
    public func addAccount(alias: String, importedKey: String?) async throws {
        let key = try importedKey.map(Vault.parseSigningKey) ?? Vault.newSigningKey()
        let name = alias.trimmingCharacters(in: .whitespaces).isEmpty ? "Account \(contents.accounts.count + 1)" : alias
        let secrets = AccountSecrets(alias: name, signingKey: key, salt: Vault.zeroSalt)
        let vault = vault, current = contents
        contents = try await Task.detached { try vault.addAccount(current, secrets) }.value
        let view = try await register(secrets)
        accounts.append(view)
        await select(view.address)
    }

    public func renameAccount(_ address: String, alias: String) async throws {
        guard let account = accounts.first(where: { $0.address == address }) else { return }
        let vault = vault, current = contents
        contents = try await Task.detached { try vault.renameAccount(current, signingKey: account.signingKey, alias: alias) }.value
        if let i = accounts.firstIndex(where: { $0.address == address }) { accounts[i].alias = alias.trimmingCharacters(in: .whitespaces) }
    }

    /// Looks a token up on chain and follows it.
    public func addToken(_ address: String) async throws {
        guard let from = account?.address else { throw PxeError("no account") }
        store.addToken(try await client.tokenInfo(address.trimmingCharacters(in: .whitespaces), from: from))
        await refresh()
    }

    public func removeToken(_ address: String) {
        store.removeToken(address)
        tokens.removeAll { $0.info.address.lowercased() == address.lowercased() }
    }

    func secrets(of address: String) throws -> AccountSecrets {
        guard let account = accounts.first(where: { $0.address == address }),
              let secrets = contents.accounts.first(where: { $0.signingKey == account.signingKey }) else { throw PxeError("unknown account") }
        return secrets
    }

    func record(_ item: ActivityItem) {
        store.upsert(item)
        if item.account == selected { activity = store.activity(account: item.account) }
    }

    func setProving(_ item: ActivityItem?) {
        proving = item
        provingSince = item == nil ? nil : Date()
    }

    private func register(_ secrets: AccountSecrets) async throws -> AccountView {
        AccountView(alias: secrets.alias, address: try await client.addAccount(secrets), signingKey: secrets.signingKey, deployed: nil)
    }
}
