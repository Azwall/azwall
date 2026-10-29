import Foundation
import Testing

@testable import AzwallKit

/// A backend with one network, so the app model can open a session.
struct OneNetworkBackend: WalletBackend {
    static let network = NetworkInfo(id: "testnet-v6", label: "Testnet", aztecVersion: "6.0.0", barretenbergVersion: "6.0.0", rpcHost: "node", pxeBundle: "v6", proverSupported: true)
    func networks() -> [NetworkInfo] { [Self.network] }
    func defaultNetworkId() -> String { Self.network.id }
    func checkNode(networkId: String) throws -> NodeCheck { NodeCheck(nodeVersion: "x", rollupVersion: 0, compatible: true, mismatches: []) }
    func pxeOpenParams(networkId: String) throws -> String { "{}" }
    func prepareProver(networkId: String) throws {}
    func prove(networkId: String, request: Data) throws -> Data { request }
    func newRecoveryPhrase() throws -> String { TestPhrases.phrase }
    func normalizeRecoveryPhrase(_ phrase: String) throws -> String { try TestPhrases.normalize(phrase) }
    func accountSigningKey(phrase: String, index: Int) throws -> String { TestPhrases.key(index) }
}

/// A host whose page never answers `close`, as a busy or wedged page.
struct HangsOnClose: PxeCaller {
    let inner: FakePxe
    func call(_ method: String, _ params: JSONValue) async throws -> JSONValue {
        if method == "close" { try await Task.sleep(for: .seconds(3600)) }
        return try await inner.call(method, params)
    }
}

struct HangingLauncher: PxeLauncher {
    let pxe = FakePxe()
    func launch(_ network: NetworkInfo) async throws -> RunningPxe {
        FakeRunning(client: PxeClient(caller: HangsOnClose(inner: pxe)), pxe: pxe)
    }
}

final class LifecycleDeps: AppDependencies, @unchecked Sendable {
    let storage = MemoryStorage()
    var created = false
    var secure = true
    var wipeFails = false
    var launcherUsed: PxeLauncher = HangingLauncher()
    func backend() throws -> WalletBackend { OneNetworkBackend() }
    var vault: Vault { Vault(storage: storage) }
    var launcher: PxeLauncher { launcherUsed }
    @MainActor var approvals: DappApprovals { DappApprovals() }
    var storeDirectory: URL { FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    var lastNetwork: String? { get { nil } set {} }
    func authenticate(reason: String) async -> Bool { true }
    func wipe() throws {
        if wipeFails { throw VaultError("the store could not be removed") }
        try vault.wipe()
    }
    var walletCreated: Bool { get { created } set { created = newValue } }
    var deviceSecure: Bool { secure }
}

/// From the 2026-10-28 store-release audit (F4, F5).
@MainActor
struct LifecycleTests {
    let first = AccountSecrets(alias: "main", signingKey: "0x" + String(repeating: "ab", count: 32), salt: Vault.zeroSalt)

    @Test func lockingDoesNotWaitForThePageToAnswer() async throws {
        let deps = LifecycleDeps()
        _ = try deps.vault.create(first: first)
        let model = AppModel(deps: deps)
        await model.boot()
        await model.unlock()
        guard case .unlocked = model.phase else { Issue.record("not unlocked: \(model.phase)"); return }
        let locking = Task { await model.lock() }
        try await Task.sleep(for: .milliseconds(100))
        #expect(model.phase == .locked)
        #expect(!model.hasRecoveryPhrase && model.session == nil)
        locking.cancel()
    }

    @Test func closingIsBoundedToSeconds() {
        #expect(PxeChannel.defaultTimeout("close") == .seconds(15))
        #expect(PxeChannel.defaultTimeout("transfer") == .seconds(20 * 60))
    }

    @Test func aVaultIOSErasedIsSaidRatherThanAWelcomeScreen() async {
        let deps = LifecycleDeps()
        deps.created = true // a wallet was made here; its Keychain item is gone
        let model = AppModel(deps: deps)
        await model.boot()
        #expect(model.phase == .vaultLost)
        await model.startOver()
        #expect(model.phase == .onboarding)
        #expect(!deps.created)
    }

    @Test func aFreshInstallIsWelcomed() async {
        let model = AppModel(deps: LifecycleDeps())
        await model.boot()
        #expect(model.phase == .onboarding)
    }

    @Test func noWalletIsMadeWithoutAPasscode() async {
        let deps = LifecycleDeps()
        deps.secure = false
        let model = AppModel(deps: deps)
        await model.boot()
        await model.createWallet(secret: TestPhrases.phrase, alias: "main")
        #expect(model.message == NoPasscode().errorDescription)
        #expect(!deps.vault.exists())
    }

    @Test func aWalletCreatedIsRemembered() async {
        let deps = LifecycleDeps()
        let model = AppModel(deps: deps)
        await model.boot()
        await model.createWallet(secret: TestPhrases.phrase, alias: "main")
        #expect(deps.created)
    }

    /// Store-release audit: a removal that failed must not end on a welcome screen, as if nothing were left.
    @Test func aRemovalThatFailsSaysSoAndStaysLocked() async throws {
        let deps = LifecycleDeps()
        _ = try deps.vault.create(first: first)
        deps.created = true
        deps.wipeFails = true
        let model = AppModel(deps: deps)
        await model.boot()
        await model.wipe()
        #expect(model.phase == .locked)
        #expect(model.message == "the store could not be removed")
        #expect(deps.created)
    }
}
