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
        AppContainer.hadData = FileManager.default.fileExists(atPath: url.path)
        var failure: Error?
        do {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try url.setResourceValues(values)
        } catch {
            failure = error
        }
        // Kept with the store (and like it out of backups): what went wrong lately, exported only by the user.
        DiagnosticLog.shared.attach(file: url.appendingPathComponent("diagnostics.log"))
        // Reported once attached, which replaces what the log held: a store left in backups must be visible.
        if let failure { DiagnosticLog.shared.report("store", failure) }
        return url
    }()
    let approvals = DappApprovals()

    /// Whether the wallet's data directory was there at launch: it goes with the app, unlike the Keychain.
    // Written once, by storeDirectory's initializer during init, before anything else reads it.
    nonisolated(unsafe) private static var hadData = false
    private static let installedKey = "azwall.installed"

    init() {
        forgetEarlierInstall()
    }

    /// Keychain items outlive the app: after an uninstall and a reinstall, the old wallet would open again with the
    /// device passcode. A fresh install (no marker in UserDefaults and no data directory, both removed with the app)
    /// deletes them. An update keeps both, so it never gets here.
    private func forgetEarlierInstall() {
        _ = storeDirectory
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: Self.installedKey) else { return }
        if !Self.hadData && vault.exists() {
            do {
                try vault.wipe()
            } catch {
                DiagnosticLog.shared.report("vault", error)
            }
        }
        defaults.set(true, forKey: Self.installedKey)
    }
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
        UserDefaults.standard.removeObject(forKey: "azwall.network")
        // The PXE stores (encrypted with the vault's store key, useless without it) and the browser's sites data.
        Task { @MainActor in
            let all = WKWebsiteDataStore.allWebsiteDataTypes()
            await WKWebsiteDataStore.default().removeData(ofTypes: all, modifiedSince: .distantPast)
            await BrowserTabs.dataStore.removeData(ofTypes: all, modifiedSince: .distantPast)
        }
        // Last, so the rest is gone even if this fails; failing, it throws: addresses and history left in clear on
        // the device are something the user must be told about (AppModel shows the error).
        if FileManager.default.fileExists(atPath: storeDirectory.path) { try FileManager.default.removeItem(at: storeDirectory) }
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
            // Cancelled while proving: the proof is dropped, so the PXE never sends the transaction. Past this, it may.
            try ProofGate.shared.release()
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
