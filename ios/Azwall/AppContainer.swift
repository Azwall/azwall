import AzwallKit
import AzwallPlatform
import AzwallRust
import Foundation
import WebKit

/// The platform side of the app: Rust core, Keychain vault, PXE hosts in hidden web views.
final class AppContainer: AppDependencies, @unchecked Sendable {
    private let lock = NSLock()
    private var loaded: WalletBackend?
    let vault = Vault(storage: SlottedVaultStorage(slots: KeychainVaultSlots()))
    /// Addresses, amounts and history in clear: kept out of iCloud and computer backups (the vault itself never
    /// leaves this device), so a backup does not undo the privacy Aztec gives.
    let storeDirectory: URL = {
        var url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("wallet")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        // Kept with the store (and like it out of backups): what went wrong lately, exported only by the user.
        DiagnosticLog.shared.attach(file: url.appendingPathComponent("diagnostics.log"))
        return url
    }()
    let approvals = DappApprovals()
    var launcher: PxeLauncher {
        IosPxeLauncher(backend: { try self.backend() }, approve: approvals.handler, stores: storeDirectory.appendingPathComponent("pxe"))
    }

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
        // The PXE stores (encrypted with the vault's store key, useless without it) and the browser's sites data.
        Task { @MainActor in
            let all = WKWebsiteDataStore.allWebsiteDataTypes()
            await WKWebsiteDataStore.default().removeData(ofTypes: all, modifiedSince: .distantPast)
            await BrowserTabs.dataStore.removeData(ofTypes: all, modifiedSince: .distantPast)
        }
    }
}

/// Starts a network's PXE bundle and answers its `prove` calls with the Rust prover.
struct IosPxeLauncher: PxeLauncher {
    let backend: @Sendable () throws -> WalletBackend
    /// Answers the dApp gateway's `approve` calls (DappApprovals).
    let approve: NativeHandler
    /// Where the PXE stores' sealed copies are kept, one directory per bundle (PxeStoreFiles).
    let stores: URL

    func launch(_ network: NetworkInfo) async throws -> RunningPxe {
        guard let resources = Bundle.main.resourceURL else { throw PxeError("no app resources") }
        let backend = backend
        let prove: NativeHandler = { params in
            guard let id = params["network"]?.stringValue, let b64 = params["request"]?.stringValue, let request = Data(base64Encoded: b64) else {
                throw PxeError("malformed prove request")
            }
            try ProofGate.shared.check()
            let response = try await Task.detached(priority: .userInitiated) { try backend().prove(networkId: id, request: request) }.value
            // Cancelled while proving: the proof is dropped, so the PXE never sends the transaction.
            try ProofGate.shared.check()
            return .object(["response": .string(response.base64EncodedString())])
        }
        let files = PxeStoreFiles(directory: stores.appendingPathComponent(network.pxeBundle))
        let handlers = files.handlers.merging(["prove": prove, "approve": approve]) { _, new in new }
        let host = try await PxeHost.start(bundle: network.pxeBundle, resources: resources, handlers: handlers)
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
