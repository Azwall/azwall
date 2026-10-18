import Foundation
import Observation

/// What the app model needs from the platform; replaced by fakes in tests.
public protocol AppDependencies: Sendable {
    /// Loads the Rust core on first use: call it off the main actor.
    func backend() throws -> WalletBackend
    var vault: Vault { get }
    var launcher: PxeLauncher { get }
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
    private var contents: VaultContents?

    public init(deps: AppDependencies) {
        self.deps = deps
    }

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

    /// Creates the vault with `signingKey` (generated or imported), then opens the wallet.
    public func createWallet(signingKey: String, alias: String) async {
        await action {
            let account = AccountSecrets(alias: alias.isEmpty ? "Account 1" : alias, signingKey: try Vault.parseSigningKey(signingKey), salt: Vault.zeroSalt)
            let vault = self.deps.vault
            await self.start(try await Task.detached { try vault.create(first: account) }.value)
        }
    }

    /// Reading the vault shows the system prompt (Face ID, Touch ID or passcode).
    public func unlock() async {
        await action {
            let vault = self.deps.vault
            await self.start(try await Task.detached { try vault.unlock() }.value)
        }
    }

    public func switchNetwork(_ id: String) async {
        guard let current = session, current.network.id != id, let contents else { return }
        await current.close()
        deps.lastNetwork = id
        await start(contents)
    }

    public func lock() async {
        await session?.close()
        contents = nil
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
        await action { try self.deps.wipe() }
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

    private func start(_ unlocked: VaultContents) async {
        contents = unlocked
        let deps = deps
        let defaultId = (try? await Task.detached { try deps.backend().defaultNetworkId() }.value) ?? ""
        guard let network = networks.first(where: { $0.id == deps.lastNetwork }) ?? networks.first(where: { $0.id == defaultId }),
              let backend = try? await Task.detached(operation: { try deps.backend() }).value else {
            phase = .failed("no network is configured")
            return
        }
        deps.lastNetwork = network.id
        let session = WalletSession(
            backend: backend, launcher: deps.launcher, vault: deps.vault, contents: unlocked, storeDirectory: deps.storeDirectory, network: network
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
