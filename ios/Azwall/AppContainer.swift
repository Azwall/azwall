import AzwallKit
import AzwallPlatform
import AzwallRust
import Foundation

/// The platform side of the app: Rust core, Keychain vault, PXE hosts in hidden web views.
final class AppContainer: AppDependencies, @unchecked Sendable {
    private let lock = NSLock()
    private var loaded: WalletBackend?
    let vault = Vault(storage: KeychainVaultStorage())
    let storeDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("wallet")
    let approvals = DappApprovals()
    var launcher: PxeLauncher { IosPxeLauncher(backend: { try self.backend() }, approve: approvals.handler) }

    var lastNetwork: String? {
        get { UserDefaults.standard.string(forKey: "azwall.network") }
        set { UserDefaults.standard.set(newValue, forKey: "azwall.network") }
    }

    func backend() throws -> WalletBackend {
        try lock.withLock {
            if let loaded { return loaded }
            let created = try AzwallRuntime.create()
            loaded = created
            return created
        }
    }

    func authenticate(reason: String) async -> Bool {
        await DeviceOwner.authenticate(reason: reason)
    }

    func wipe() throws {
        try vault.wipe()
        try? FileManager.default.removeItem(at: storeDirectory)
        UserDefaults.standard.removeObject(forKey: "azwall.network")
    }
}

/// Starts a network's PXE bundle and answers its `prove` calls with the Rust prover.
struct IosPxeLauncher: PxeLauncher {
    let backend: @Sendable () throws -> WalletBackend
    /// Answers the dApp gateway's `approve` calls (DappApprovals).
    let approve: NativeHandler

    func launch(_ network: NetworkInfo) async throws -> RunningPxe {
        guard let resources = Bundle.main.resourceURL else { throw PxeError("no app resources") }
        let backend = backend
        let prove: NativeHandler = { params in
            guard let id = params["network"]?.stringValue, let b64 = params["request"]?.stringValue, let request = Data(base64Encoded: b64) else {
                throw PxeError("malformed prove request")
            }
            let response = try await Task.detached(priority: .userInitiated) { try backend().prove(networkId: id, request: request) }.value
            return .object(["response": .string(response.base64EncodedString())])
        }
        let host = try await PxeHost.start(bundle: network.pxeBundle, resources: resources, handlers: ["prove": prove, "approve": approve])
        return RunningHost(host: host)
    }
}

private struct RunningHost: RunningPxe {
    let host: PxeHost
    let client: PxeClient

    init(host: PxeHost) {
        self.host = host
        client = PxeClient(caller: host.channel)
    }

    func events() async -> AsyncStream<PxeEvent> {
        await host.channel.events()
    }

    func stop() async {
        await host.stop()
    }
}
