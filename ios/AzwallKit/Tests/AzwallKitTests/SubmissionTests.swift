import Foundation
import Testing
@testable import AzwallKit

/// From the 2026-10-28 independent audit (A03, A06): once its proof is handed over, a transaction may reach the
/// network at any moment. A cancel can no longer say nothing was sent, and the wallet keeps its hash from then on.
@MainActor
struct SubmissionTests {
    let token = TokenInfo(address: "0xt", name: "Test", symbol: "TST", decimals: 6)
    let recipient = "0x" + String(repeating: "cd", count: 32)

    @Test func aTransactionWhoseProofWasReleasedCannotBeCancelled() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try SessionTests().session(pxe)
        await s.open()
        var refused = false
        pxe.whileSending = {
            let cancelled = await s.cancelProving()
            refused = !cancelled
        }
        try await s.send(.private, token: token, to: recipient, amount: "1")
        #expect(refused)
        #expect(s.activity.first?.status == .pending)
        #expect(s.activity.first?.txHash == "0xhash1")
    }

    /// And the store-release audit (F7): failing once the node has it, the send is followed, not called failed.
    @Test func aSendThatFailsAfterTheHashWasToldIsFollowed() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try SessionTests().session(pxe)
        await s.open()
        pxe.whileSending = {
            pxe.emit.yield(PxeEvent(name: "txSubmitting", data: try JSONValue.parse(#"{"from":"ADDR-ABAB","txHash":"0xtold"}"#)))
            try await Task.sleep(for: .milliseconds(50))
            throw PxeError("the answer was lost")
        }
        try await s.send(.private, token: token, to: recipient, amount: "1")
        #expect(s.activity.first?.txHash == "0xtold")
        #expect(s.activity.first?.status != .failed)
    }

    /// Store-release audit (F13) and open-test audit (OT-01): a transaction left unanswered before its proof left must
    /// never be sent behind that failure, even by the proof finishing after a retry started. Its host is stopped for good.
    @Test func aTransactionLeftUnansweredIsNeverSentLater() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try SessionTests().session(pxe)
        await s.open()
        pxe.unanswered = ["transfer"]
        await #expect(throws: PxeError.self) { try await s.send(.private, token: token, to: recipient, amount: "1") }
        #expect(s.phase == .failed)
        #expect(s.error == WalletSession.stuck)
        // A retry on this session cannot start, so nothing reopens the gate for the old proof.
        pxe.unanswered = []
        await #expect(throws: PxeError.self) { try await s.send(.private, token: token, to: recipient, amount: "2") }
        #expect(throws: CancelledByUser.self) { try pxe.gate.release() }
        #expect(pxe.sent.isEmpty)
    }

    /// Store-release re-audit (R1): a send the page refused must not leave every dApp proof after it refused as cancelled.
    @Test func aSendThePageRefusedLetsTheNextDappProofThrough() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try SessionTests().session(pxe)
        await s.open()
        pxe.failing = ["transfer"]
        await #expect(throws: PxeError.self) { try await s.send(.private, token: token, to: recipient, amount: "1") }
        try pxe.gate.check()
        try pxe.gate.release()
        #expect(!pxe.gate.cancelled)
    }

    /// Open-test audit (OT-02): with two dApp transactions of one account queued, the hash goes to the one sent.
    @Test func queuedDappSendsKeepTheirHash() async throws {
        let pxe = FakePxe()
        let s = try SessionTests().session(pxe)
        await s.open()
        for id in ["first", "second"] {
            pxe.emit.yield(PxeEvent(name: "dappTx", data: try JSONValue.parse(#"{"id":"\#(id)","account":"addr-abab","origin":"https://x.example","summary":"vote","status":"proving"}"#)))
        }
        try await Task.sleep(for: .milliseconds(50))
        pxe.emit.yield(PxeEvent(name: "txSubmitting", data: try JSONValue.parse(#"{"from":"addr-abab","txHash":"0xaccepted","id":"first"}"#)))
        try await Task.sleep(for: .milliseconds(50))
        #expect(s.store.item(id: "first")?.txHash == "0xaccepted")
        #expect(s.store.item(id: "second")?.txHash == nil)
    }

    @Test func aTransactionInterruptedWhileSendingIsFollowedAfterARestart() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = WalletStore(directory: dir, networkId: "testnet-v6")
        var sending = ActivityItem(account: "addr-abab", kind: .sendPrivate)
        sending.txHash = "0xtold"
        let proving = ActivityItem(account: "addr-abab", kind: .sendPrivate)
        store.upsert(sending)
        store.upsert(proving)
        store.failInterrupted()
        #expect(store.item(id: sending.id)?.status == .pending)
        #expect(store.item(id: proving.id)?.status == .failed)
    }
}

/// From the 2026-10-28 audits: a Retry or a deletion while an account is being saved must not lose it or write the
/// deleted vault back. The save is held mid-way, as Face ID holds it on a phone.
struct VaultStateTests {
    /// Holds every write after the first until `release` is called.
    final class HeldStorage: VaultStorage, @unchecked Sendable {
        let inner = MemoryStorage()
        private let lock = NSLock()
        private var writes = 0
        let entered = DispatchSemaphore(value: 0)
        let released = DispatchSemaphore(value: 0)
        func exists() -> Bool { inner.exists() }
        func read() throws -> Data { try inner.read() }
        func write(_ data: Data) throws {
            let held = lock.withLock { writes += 1; return writes > 1 }
            if held {
                entered.signal()
                released.wait()
            }
            try inner.write(data)
        }
        func delete() throws { try inner.delete() }

        /// Blocks until a held write has entered `write`. Synchronous on purpose: Swift refuses a semaphore's `wait()`
        /// in an async context, a detached task's closure included, so the test calls this from one instead.
        func waitForHeldWrite() { entered.wait() }
    }

    let first = AccountSecrets(alias: "first", signingKey: "0x" + String(repeating: "01", count: 32), salt: Vault.zeroSalt)
    let second = AccountSecrets(alias: "second", signingKey: "0x" + String(repeating: "02", count: 32), salt: Vault.zeroSalt)

    @Test func aChangeAfterOneStillSavingStartsFromWhatThatOneSaved() async throws {
        let storage = HeldStorage()
        let vault = Vault(storage: storage)
        let state = VaultState(vault: vault, unlocked: try vault.create(first: first))
        let second = second
        let adding = Task { try await state.change { vault, current in (try vault.addAccount(current, second), ()) } }
        await Task.detached { storage.waitForHeldWrite() }.value
        let third = AccountSecrets(alias: "third", signingKey: "0x" + String(repeating: "03", count: 32), salt: Vault.zeroSalt)
        let addingThird = Task { try await state.change { vault, current in (try vault.addAccount(current, third), ()) } }
        storage.released.signal()
        try await adding.value
        storage.released.signal()
        try await addingThird.value
        #expect(try vault.unlock().accounts.map(\.alias) == ["first", "second", "third"])
    }

    @Test func deletingTheWalletWhileSavingLeavesNoVault() async throws {
        let storage = HeldStorage()
        let vault = Vault(storage: storage)
        let state = VaultState(vault: vault, unlocked: try vault.create(first: first))
        let second = second
        let adding = Task { try await state.change { vault, current in (try vault.addAccount(current, second), ()) } }
        await Task.detached { storage.waitForHeldWrite() }.value
        let erasing = Task { try await state.erase { try vault.wipe() } }
        storage.released.signal()
        try await adding.value
        try await erasing.value
        #expect(!vault.exists())
        #expect(state.current == nil)
    }
}
