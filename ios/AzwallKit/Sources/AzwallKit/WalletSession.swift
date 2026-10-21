import Foundation
import Observation

/// A running PXE host for one network.
public protocol RunningPxe: Sendable {
    var client: PxeClient { get }
    /// The host's events (logs, dApp messages…), from now on.
    func events() async -> AsyncStream<PxeEvent>
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
    public internal(set) var accounts: [AccountView] = []
    public internal(set) var selected: String?
    public internal(set) var tokens: [TokenView] = []
    public internal(set) var activity: [ActivityItem] = []
    /// Tokens the account holds that are not followed yet, found among the contracts dApps registered.
    public internal(set) var found: [FoundToken] = []
    /// The token the user chose to show at the top (an address), if any.
    public private(set) var primary: String?
    /// Tokens the selected account created (it mints them), as addresses.
    public private(set) var created: [String] = []
    /// True while the shown balances come from the last session (AccountSnapshot), until a refresh confirms them.
    public internal(set) var stale = false
    /// What runs in the background right now, for the status line (nil when idle).
    public internal(set) var task: BackgroundTask?
    /// The browser's link to the PXE host's dApp gateway.
    public let dapps = DappLink()
    public private(set) var refreshing = false
    public private(set) var error: String?
    public private(set) var proving: ActivityItem?
    public private(set) var provingSince: Date?

    public var account: AccountView? { accounts.first { $0.address == selected } }

    /// The token shown at the top: the one the user chose while it is followed, else the first holding something,
    /// else the first. Aztec has no native token and the wallet no prices, so there is no total to show instead.
    public var hero: TokenView? {
        tokens.first { $0.info.address.lowercased() == primary?.lowercased() }
            ?? tokens.first { $0.total.map(Amounts.isPositive) == true }
            ?? tokens.first
    }

    /// Shows `address` at the top of the home screen for the selected account.
    public func choosePrimary(_ address: String) {
        guard let selected else { return }
        store.setPrimary(account: selected, token: address)
        primary = address
    }

    private let backend: WalletBackend
    private let launcher: PxeLauncher
    private let vault: Vault
    private var contents: VaultContents
    let store: WalletStore
    private var pxe: RunningPxe?
    @ObservationIgnored private var eventLoop: Task<Void, Never>?
    @ObservationIgnored var lastDiscovery: Date?

    public init(backend: WalletBackend, launcher: PxeLauncher, vault: Vault, contents: VaultContents, storeDirectory: URL, network: NetworkInfo) {
        self.backend = backend
        self.launcher = launcher
        self.vault = vault
        self.contents = contents
        self.network = network
        store = WalletStore(directory: storeDirectory, networkId: network.id)
        dapps.session = self
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
        store.seed(network.knownTokens)
        restoreSnapshot()
        let backend = backend, id = network.id
        Task.detached { try? backend.prepareProver(networkId: id) }
        do {
            let running = try await launcher.launch(network)
            pxe = running
            await listen(running)
            let params = try await Task.detached { try backend.pxeOpenParams(networkId: id) }.value
            check = try await running.client.open(networkParamsJSON: params, dbKey: contents.dbKey)
            // Registering keeps what the snapshot knew of each account until the refresh reads it again.
            let known = accounts
            var views: [AccountView] = []
            for secrets in contents.accounts {
                var view = try await register(secrets)
                view.deployed = known.first { $0.address == view.address }?.deployed
                views.append(view)
            }
            accounts = views
            selected = selected.flatMap { sel in views.contains { $0.address == sel } ? sel : nil } ?? views.first?.address
            // The PXE store can start over (a pool damaged by a crash is set aside): register followed tokens again.
            try? await running.client.registerTokens(store.tokens.map(\.address))
            resume(running.client)
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
        eventLoop?.cancel()
        try? await running.client.close()
        await running.stop()
    }

    public func select(_ address: String) async {
        guard accounts.contains(where: { $0.address == address }) else { return }
        selected = address
        tokens = tokens.map { TokenView(info: $0.info, balance: nil) }
        found = []
        lastDiscovery = nil
        primary = store.primary(account: address)
        await refresh()
    }

    /// Re-reads deployment status, balances and history of the selected account.
    public func refresh() async {
        guard let account, !refreshing, let client = try? self.client else { return }
        refreshing = true
        task = .syncing(from: try? await client.syncStatus())
        do {
            let deployed = try await client.isDeployed(account.address)
            let followed = store.tokens
            let balances = deployed && !followed.isEmpty ? try await client.balances(owner: account.address, tokens: followed.map(\.address)) : []
            if let i = accounts.firstIndex(where: { $0.address == account.address }) { accounts[i].deployed = deployed }
            tokens = followed.map { info in TokenView(info: info, balance: balances.first { $0.token.lowercased() == info.address.lowercased() }) }
            activity = store.activity(account: account.address)
            primary = store.primary(account: account.address)
            created = store.created(account: account.address)
            stale = false
            error = nil
            saveSnapshot()
        } catch {
            self.error = error.localizedDescription
        }
        refreshing = false
        task = nil
        // Independent of the balances: a token that cannot be read must not hide the others found.
        if self.account?.deployed == true { await discover(owner: account.address) }
    }

    /// Routes the host's events for as long as it runs: dApp messages, sessions and transactions.
    private func listen(_ running: RunningPxe) async {
        let events = await running.events()
        eventLoop = Task { [weak self] in
            for await event in events {
                guard let self else { return }
                _ = self.dapps.handle(event)
            }
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

    /// The vault's accounts, for the snapshot (Snapshot.swift).
    var vaultAccounts: [AccountSecrets] { contents.accounts }

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
