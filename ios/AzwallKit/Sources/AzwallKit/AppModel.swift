import Foundation
import Observation

/// What the app model needs from the platform; replaced by fakes in tests.
public protocol AppDependencies: Sendable {
    /// Loads the Rust core on first use: call it off the main actor.
    func backend() throws -> WalletBackend
    var vault: Vault { get }
    var launcher: PxeLauncher { get }
    /// dApp requests waiting for the user; the PXE hosts' `approve` calls land here.
    @MainActor var approvals: DappApprovals { get }
    var storeDirectory: URL { get }
    var lastNetwork: String? { get nonmutating set }
    /// Asks for Face ID, Touch ID or the passcode (before revealing a key or wiping).
    func authenticate(reason: String) async -> Bool
    /// Deletes the vault and the wallet's local data.
    func wipe() throws
}

public enum AppPhase: Equatable {
    case booting, onboarding, locked, failed(String)
    case unlocked(WalletSession)

    public static func == (a: AppPhase, b: AppPhase) -> Bool {
        switch (a, b) {
        case (.booting, .booting), (.onboarding, .onboarding), (.locked, .locked): true
        case (.failed(let x), .failed(let y)): x == y
        case (.unlocked(let x), .unlocked(let y)): x === y
        default: false
        }
    }
}

/// The app's lifecycle: onboarding, unlocking the vault, and one `WalletSession` for the selected network
/// (switching network replaces it). Views read `phase` and the session's own state.
@MainActor
@Observable
public final class AppModel {
    public private(set) var phase: AppPhase = .booting
    public private(set) var networks: [NetworkInfo] = []
    public private(set) var busy = false
    /// Outcome of the last user action, shown once by the UI.
    public var message: String?
    public private(set) var nodes: [String: NodeCheck] = [:]

    private let deps: AppDependencies
    /// The unlocked vault, owned here once for every session (VaultState).
    @ObservationIgnored private let vaultState: VaultState

    public init(deps: AppDependencies) {
        self.deps = deps
        vaultState = VaultState(vault: deps.vault)
    }

    public var approvals: DappApprovals { deps.approvals }

    public var session: WalletSession? {
        if case .unlocked(let s) = phase { return s }
        return nil
    }

    public func boot() async {
        guard phase == .booting else { return }
        let deps = deps
        do {
            let (list, exists) = try await Task.detached { (try deps.backend().networks(), deps.vault.exists()) }.value
            networks = list
            phase = exists ? .locked : .onboarding
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    /// Creates the wallet from `secret`: a recovery phrase (new or restored), or a signing key. Then opens it.
    public func createWallet(secret: String, alias: String) async {
        await action {
            let deps = self.deps
            let (account, phrase) = try await Task.detached { try Recovery.firstAccount(secret, alias: alias, backend: try deps.backend()) }.value
            _ = try await self.vaultState.open { try await Task.detached { try deps.vault.create(first: account, phrase: phrase) }.value }
            await self.start()
        }
    }

    /// A new recovery phrase for onboarding; the Rust core loads on first use, off the main actor.
    public func newRecoveryPhrase() async throws -> String {
        let deps = deps
        return try await Task.detached { try deps.backend().newRecoveryPhrase() }.value
    }

    /// Nil when `secret` is a usable phrase or key, else what is wrong with it.
    public func checkSecret(_ secret: String) async -> String? {
        let deps = deps
        return await Task.detached { () -> String? in
            do { _ = try Recovery.firstAccount(secret, alias: "", backend: try deps.backend()); return nil } catch { return error.localizedDescription }
        }.value
    }

    /// False for a wallet created from a signing key, before recovery phrases.
    public var hasRecoveryPhrase: Bool { vaultState.current?.phrase != nil }

    /// Re-authenticates, then returns the recovery phrase (nil for a wallet created from a key).
    public func revealPhrase() async -> String? {
        guard await deps.authenticate(reason: "Show the recovery phrase") else { return nil }
        return vaultState.current?.phrase
    }

    /// Reading the vault shows the system prompt (Face ID, Touch ID or passcode).
    public func unlock() async {
        await action {
            let vault = self.deps.vault
            // After any save still running: it reads the vault that save leaves.
            _ = try await self.vaultState.open { try await Task.detached { try vault.unlock() }.value }
            await self.start()
        }
    }

    public func switchNetwork(_ id: String) async {
        guard let current = session, current.network.id != id, vaultState.current != nil else { return }
        // The host that asked is going away: its pending requests cannot be answered.
        deps.approvals.rejectAll()
        await current.close()
        deps.lastNetwork = id
        await start()
    }

    /// Starts the network's session again: after it failed to open, or its PXE host stopped.
    public func restart() async {
        guard let current = session, vaultState.current != nil else { return }
        deps.approvals.rejectAll()
        await current.close()
        await start()
    }

    @ObservationIgnored private var autoLock = AutoLock()

    /// Locks once the app has been in the background for the auto-lock delay, if iOS lets it run that long.
    @ObservationIgnored private var backgroundLock: Task<Void, Never>?

    public func onBackground() async {
        autoLock.backgrounded()
        // The keys would otherwise stay in memory, in the PXE's web view too, while the app is kept alive.
        backgroundLock?.cancel()
        backgroundLock = Task { [weak self] in
            // Cancelled when the app comes back first: then nothing is locked.
            try? await Task.sleep(for: .seconds(AutoLock.defaultTimeout))
            guard !Task.isCancelled, let self, let session = self.session, session.proving == nil else { return }
            await self.lock()
        }
        await session?.flush()
    }

    /// Back from the background: locks if away too long, unless a transaction is being proven or sent.
    public func onForeground() async {
        backgroundLock?.cancel()
        backgroundLock = nil
        if autoLock.foregrounded(), let session, session.proving == nil { await lock() }
    }

    public func lock() async {
        deps.approvals.rejectAll()
        await session?.close()
        vaultState.forget()
        phase = .locked
    }

    /// Re-authenticates, then returns the signing key of the account at `address` (for backup).
    public func revealKey(address: String) async -> String? {
        guard await deps.authenticate(reason: "Show the secret key") else { return nil }
        return session?.accounts.first { $0.address == address }?.signingKey
    }

    public func wipe() async {
        guard await deps.authenticate(reason: "Remove the wallet from this device") else { return }
        await lock()
        // After any save under way, which would otherwise write the deleted vault back.
        let deps = deps
        await action { try await self.vaultState.erase { try deps.wipe() } }
        phase = .onboarding
    }

    public func checkNode(_ networkId: String) async {
        let deps = deps
        await action {
            let check = try await Task.detached { try deps.backend().checkNode(networkId: networkId) }.value
            self.nodes[networkId] = check
        }
    }

    public func loadProver(_ networkId: String) async {
        let deps = deps
        await action(done: "Prover loaded.") { try await Task.detached { try deps.backend().prepareProver(networkId: networkId) }.value }
    }

    /// Runs a wallet action (transaction, token…) and reports its outcome in `message`.
    public func run(done: String? = nil, _ block: @escaping (WalletSession) async throws -> Void) async {
        await action(done: done) {
            guard let session = self.session else { throw VaultError("the wallet is locked") }
            try await block(session)
        }
    }

    /// A transaction the user confirmed: Face ID, Touch ID or the passcode first, as for unlocking. Declined, nothing
    /// is proven or sent.
    /// `onAuthorized` runs once the owner confirmed: the form closes then, so a declined prompt leaves it as typed.
    public func sign(done: String? = nil, onAuthorized: () -> Void = {}, _ block: @escaping (WalletSession) async throws -> Void) async {
        // Busy from the first tap: a second one must not queue a second prompt and transaction.
        busy = true
        guard await confirmOwner() else {
            busy = false
            message = "authentication was cancelled"
            return
        }
        onAuthorized()
        await run(done: done, block)
    }

    /// Face ID, Touch ID or the passcode for a transaction about to be sent (the wallet's own, or a dApp's).
    public func confirmOwner() async -> Bool {
        await deps.authenticate(reason: "Confirm the transaction")
    }

    /// Opens the session of the last network on the unlocked vault (which it reads from `vaultState`, not a copy).
    private func start() async {
        let deps = deps
        let backend: WalletBackend
        do {
            backend = try await Task.detached { try deps.backend() }.value
        } catch {
            // Without the Rust core nothing opens: the user is told why, not that no network is configured.
            phase = .failed(error.localizedDescription)
            return
        }
        let defaultId = backend.defaultNetworkId()
        guard let network = networks.first(where: { $0.id == deps.lastNetwork }) ?? networks.first(where: { $0.id == defaultId }) else {
            phase = .failed("no network is configured")
            return
        }
        deps.lastNetwork = network.id
        let session = WalletSession(
            backend: backend, launcher: deps.launcher, vaultState: vaultState, storeDirectory: deps.storeDirectory, network: network
        )
        phase = .unlocked(session)
        await session.open()
        await checkNode(network.id)
    }

    private func action(done: String? = nil, _ block: () async throws -> Void) async {
        busy = true
        defer { busy = false }
        do {
            try await block()
            if let done { message = done }
        } catch {
            message = error.localizedDescription
        }
    }
}
