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
    public internal(set) var primary: String?
    /// Tokens the selected account created (it mints them), as addresses.
    public internal(set) var created: [String] = []
    /// True while the shown balances come from the last session (AccountSnapshot), until a refresh confirms them.
    public internal(set) var stale = false
    /// The network runs a new chain since last time (WalletStore.move): shown until the user acknowledges it.
    public internal(set) var chainReset = false
    /// What runs in the background right now, for the status line (nil when idle).
    public internal(set) var task: BackgroundTask?
    /// The browser's link to the PXE host's dApp gateway.
    public let dapps = DappLink()
    public private(set) var refreshing = false
    /// Set by close(): a host that finishes starting afterwards is stopped at once.
    @ObservationIgnored private var closed = false
    /// Set when a refresh is asked while one runs: the running one goes round again.
    @ObservationIgnored private var refreshAgain = false
    public private(set) var error: String?
    public private(set) var proving: ActivityItem?
    public private(set) var provingSince: Date?
    /// Where fees are paid with Fee Juice: base units the selected account holds (Fees).
    public internal(set) var feeJuice: String?
    /// Fee Juice bridged to the selected account from Ethereum, claimed or not (FeeJuiceDeposit).
    public internal(set) var deposits: [FeeJuiceDeposit] = []
    /// Releases the transaction being proven when the user cancels it (Transactions.swift).
    @ObservationIgnored var stopProving: (() -> Void)?
    /// A cancelled transaction still being proven in the background; the next one waits for its proof to be refused.
    @ObservationIgnored var winding: Task<Void, Never>?

    public var account: AccountView? { accounts.first { $0.address == selected } }

    /// The selected account's activation is sent and not in a block yet: activating again would pay twice.
    public var activating: Bool {
        activity.contains { $0.kind == .deployAccount && $0.account == selected && ($0.status == .proving || $0.status == .pending) }
    }

    /// The first deposit the wallet can claim now, if any: activation claims it in the same transaction.
    public var claimable: FeeJuiceDeposit? { deposits.first { $0.claimable } }

    /// Base units bridged to the account and not on Aztec yet.
    public var arriving: String { deposits.filter(\.arriving).map(\.amount).reduce("0", Amounts.add) }

    /// Deposits made with a secret the wallet does not know: the user can enter it.
    public var locked: [FeeJuiceDeposit] { deposits.filter(\.locked) }

    /// The account's Fee Juice, as the first token of the home screen: 0 until read, as a new account holds.
    public var feeJuiceToken: TokenView { FeeJuice.token(feeJuice ?? "0") }

    /// What the top of the home screen can show: Fee Juice, then the followed tokens.
    public var shown: [TokenView] { [feeJuiceToken] + tokens }

    /// The token shown at the top: the one the user chose while it is followed, else Fee Juice, Aztec's fee token.
    public var hero: TokenView {
        shown.first { $0.info.address.lowercased() == primary?.lowercased() } ?? feeJuiceToken
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

    /// Refuses the proof of a cancelled transaction; the app's prove handler checks the shared one.
    let gate: ProofGate

    public init(
        backend: WalletBackend, launcher: PxeLauncher, vault: Vault, contents: VaultContents, storeDirectory: URL, network: NetworkInfo,
        gate: ProofGate = .shared
    ) {
        self.gate = gate
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
            // Locked or switched away while the host started: nobody would ever stop it.
            if closed { await running.stop(); return }
            pxe = running
            await listen(running)
            let params = try await Task.detached { try backend.pxeOpenParams(networkId: id) }.value
            check = try await running.client.open(networkParamsJSON: params, dbKey: contents.dbKey)
            if let check { adopt(check) }
            // Registering keeps what the snapshot knew of each account until the refresh reads it again.
            let known = accounts
            var views: [AccountView] = []
            for secrets in contents.accounts {
                var view = try await register(secrets)
                view.deployed = known.first { $0.address == view.address }?.deployed
                views.append(view)
            }
            accounts = views
            rememberAccounts(views)
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

    /// A network reset since last time drops what the screen shows of the old chain (WalletStore.move).
    private func adopt(_ check: NetworkCheck) {
        guard store.move(to: WalletStore.chain(of: check), known: network.knownTokens) else { return }
        chainReset = true
        stale = false
        found = []
        primary = nil
        created = []
        activity = []
        tokens = store.tokens.map { TokenView(info: $0, balance: nil) }
    }

    public func acknowledgeChainReset() { chainReset = false }

    /// The app goes to the background, where it may be killed without notice: the PXE writes what it holds in memory
    /// now rather than at its next debounce.
    public func flush() async {
        try? await pxe?.client.flush()
    }

    public func close() async {
        closed = true
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
        feeJuice = nil
        deposits = []
        await refresh()
    }

    /// Re-reads deployment status, balances and history of the selected account. A refresh asked while one runs
    /// (an account switch, a confirmed transaction) runs right after it rather than being dropped.
    public func refresh() async {
        guard !refreshing else { refreshAgain = true; return }
        refreshing = true
        repeat {
            refreshAgain = false
            await refreshSelected()
        } while refreshAgain
        refreshing = false
    }

    /// One pass for the account selected when it starts. If the user picks another one meanwhile, the results are
    /// dropped rather than shown (and saved) as the new account's: select() asked for the pass that reads it.
    private func refreshSelected() async {
        guard let account, let client = try? self.client else { return }
        let current = { self.selected == account.address }
        task = .syncing(from: try? await client.syncStatus())
        do {
            let deployed = try await client.isDeployed(account.address)
            let followed = store.tokens
            let balances = deployed && !followed.isEmpty ? try await client.balances(owner: account.address, tokens: followed.map(\.address)) : []
            if let i = accounts.firstIndex(where: { $0.address == account.address }) { accounts[i].deployed = deployed }
            if current() {
                tokens = followed.map { info in TokenView(info: info, balance: balances.first { $0.token.lowercased() == info.address.lowercased() }) }
                activity = store.activity(account: account.address)
                primary = store.primary(account: account.address)
                created = store.created(account: account.address)
                stale = false
                error = nil
                saveSnapshot()
            }
        } catch {
            if current() { self.error = error.localizedDescription }
        }
        task = nil
        // Independent of the balances: a token that cannot be read must not hide the others found.
        if current() && self.account?.deployed == true { await discover(owner: account.address) }
        // What was bridged to it: claimed by its activation, or by itself once active.
        if current(), let found = try? await client.feeJuiceDeposits(account.address, secrets: store.claimSecrets(account: account.address)), current() {
            deposits = found
        }
        // Read on every network: it is the first token shown, and what pays fees where there is no sponsor.
        if current(), let balance = try? await client.feeJuiceBalance(account.address), current() {
            feeJuice = balance
            saveFeeJuice(account, balance)
        }
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
        let secrets = try Recovery.nextAccount(contents, alias: alias, importedKey: importedKey, backend: backend)
        let vault = vault, current = contents
        contents = try await Task.detached { try vault.addAccount(current, secrets) }.value
        let view = try await register(secrets)
        accounts.append(view)
        await select(view.address)
    }

    public func renameAccount(_ address: String, alias rawAlias: String) async throws {
        let alias = rawAlias.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let account = accounts.first(where: { $0.address == address }) else { return }
        let vault = vault, current = contents
        contents = try await Task.detached { try vault.renameAccount(current, signingKey: account.signingKey, alias: alias) }.value
        if let i = accounts.firstIndex(where: { $0.address == address }) { accounts[i].alias = alias }
    }

    /// Looks a token up on chain and follows it.
    public func addToken(_ address: String) async throws {
        guard let from = account?.address else { throw PxeError("no account") }
        store.addToken(try await client.tokenInfo(address.trimmingCharacters(in: .whitespacesAndNewlines), from: from))
        await refresh()
    }

    /// Remembers a claim secret the user entered for the selected account's bridge deposits (hex or decimal), and reads
    /// the deposits again: the one made with it becomes claimable. Throws on a value that is no field element.
    public func addClaimSecret(_ text: String) async throws {
        guard let selected else { throw PxeError("no account") }
        store.addClaimSecret(account: selected, secret: try ClaimSecrets.normalize(text))
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
