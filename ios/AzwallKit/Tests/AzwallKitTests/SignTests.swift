import Foundation
import Testing

@testable import AzwallKit

/// Dependencies whose owner check answers `owner`; nothing else is used.
struct OwnerOnlyDeps: AppDependencies {
    let owner: Bool
    func backend() throws -> WalletBackend { FakeBackend() }
    var vault: Vault { Vault(storage: MemoryStorage()) }
    var launcher: PxeLauncher { FakeLauncher(pxe: FakePxe()) }
    @MainActor var approvals: DappApprovals { DappApprovals() }
    var storeDirectory: URL { FileManager.default.temporaryDirectory }
    var lastNetwork: String? { get { nil } nonmutating set {} }
    func authenticate(reason: String) async -> Bool { owner }
    func wipe() throws {}
}

@MainActor
struct SignTests {
    @Test func aDeclinedFaceIdSendsNothing() async {
        let model = AppModel(deps: OwnerOnlyDeps(owner: false))
        var sent = false
        await model.sign { _ in sent = true }
        #expect(!sent)
        #expect(model.message == "authentication was cancelled")
    }

    @Test func theOwnerGetsThrough() async {
        let model = AppModel(deps: OwnerOnlyDeps(owner: true))
        await model.sign { _ in }
        // Past the owner check: without an unlocked wallet, the transaction stops there.
        #expect(model.message == "the wallet is locked")
    }
}
