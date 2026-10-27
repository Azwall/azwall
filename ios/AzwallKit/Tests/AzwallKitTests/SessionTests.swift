import Foundation
import Testing

@testable import AzwallKit

/// A PXE host in memory: addresses derive from keys, balances and deployment are settable.
final class FakePxe: PxeCaller, @unchecked Sendable {
    var deployed = false
    /// The node's reads (deployment, balances) fail, as with an unreachable node.
    var failBalances = false
    /// What the network declares: "sponsored", or "feeJuice" (the account pays).
    var fees = "sponsored"
    /// Base units of Fee Juice every account holds.
    var feeJuice = "1500000000000000000"
    /// What feeJuiceDeposits answers: Fee Juice bridged to the account (JSON list).
    var deposits = "[]"
    /// A secret and the deposits read once it is among the secrets tried, as a deposit made with it reads.
    var unlockedBy: (secret: String, deposits: String)?
    /// The rollup version the node reports: a new one means the network was redeployed.
    var rollup = 2
    var failOpen: String?
    var sent: [JSONValue] = []
    var calls: [(String, JSONValue)] = []
    var waitError: String?
    var receiptOutcome = "success"
    var discovered = #"[{"address":"0xpal","name":"Palmyr USD","symbol":"PUSD","decimals":6,"private":"0","public":"5000000"}]"#
    let (events, emit) = AsyncStream<PxeEvent>.makeStream()
    /// Holds the first account's balances until `release` yields: a refresh still running when the user switches.
    var holdBalances = false
    /// Run inside "transfer", like the user tapping Cancel while it is proven; the app's prove handler then checks.
    var whileProving: (() async -> Void)?
    /// This fake's own gate, so that tests running in parallel do not cancel each other's transactions.
    let gate = ProofGate()
    let (released, release) = AsyncStream<Void>.makeStream()
    /// Holds every receipt until `releaseReceipts` yields: a transaction stays pending as long as a test needs it to.
    var holdReceipts = false
    let (receiptsReleased, releaseReceipts) = AsyncStream<Void>.makeStream()

    func call(_ method: String, _ params: JSONValue) async throws -> JSONValue {
        func p(_ k: String) -> String { params[k]?.stringValue ?? "" }
        calls.append((method, params))
        switch method {
        case "open":
            if let failOpen { throw PxeError("open: \(failOpen)") }
            return try JSONValue.parse(#"{"nodeVersion":"6.0.0","l1ChainId":1,"rollupVersion":\#(rollup),"vkTreeRoot":"0x","protocolContractsHash":"0x","blockNumber":10,"fees":"\#(fees)"}"#)
        case "feeJuiceBalance": return .object(["balance": .string(feeJuice)])
        case "feeJuiceDeposits":
            let secrets = params["secrets"]?.arrayValue?.compactMap(\.stringValue) ?? []
            let unlocked = unlockedBy.flatMap { secrets.contains($0.secret) ? $0.deposits : nil }
            return .object(["deposits": try JSONValue.parse(unlocked ?? deposits)])
        case "addAccount": return .object(["address": .string("addr-" + p("signingKey").suffix(4))])
        case "accountState":
            if failBalances { throw PxeError("node unreachable") }
            return .object(["deployed": .bool(deployed)])
        case "balances":
            if failBalances { throw PxeError("node unreachable") }
            let first = p("owner") == "addr-abab"
            if first && holdBalances { for await _ in released { break } }
            return try JSONValue.parse(#"[{"token":"0xt","private":"\#(first ? "2500000" : "7000000")","public":"0"}]"#)
        case "tokenInfo": return .object(["address": .string(p("token")), "name": .string("Test"), "symbol": .string("TST"), "decimals": .number(6)])
        case "mint": return .object(["txHash": .string("0xmint")])
        case "deployAccount", "transfer", "claimFeeJuice":
            await whileProving?()
            try gate.check()
            sent.append(params)
            return .object(["txHash": .string("0xhash\(sent.count)")])
        case "waitForTx":
            if holdReceipts { for await _ in receiptsReleased { break } }
            if let waitError { throw PxeError(waitError) }
            return .object(["txHash": .string(p("txHash")), "status": .string("proposed"), "blockNumber": .number(11)])
        case "txReceipt":
            return .object(["txHash": .string(p("txHash")), "status": .string("proposed"), "blockNumber": .number(12), "outcome": .string(receiptOutcome)])
        case "discoverTokens":
            let known = params["known"]?.arrayValue?.compactMap(\.stringValue) ?? []
            return known.contains("0xpal") ? .array([]) : try JSONValue.parse(discovered)
        case "syncStatus": return .object(["syncedBlock": .number(10), "syncedAt": .number(1_000), "tip": .number(12)])
        default: return .null
        }
    }
}

struct FakeRunning: RunningPxe {
    let client: PxeClient
    let pxe: FakePxe
    func events() async -> AsyncStream<PxeEvent> { pxe.events }
    func stop() async {}
}

struct FakeLauncher: PxeLauncher {
    let pxe: FakePxe
    func launch(_ network: NetworkInfo) async throws -> RunningPxe { FakeRunning(client: PxeClient(caller: pxe), pxe: pxe) }
}

/// A launcher whose host starts only when `release` yields, and remembers being stopped.
final class SlowLauncher: PxeLauncher, @unchecked Sendable {
    let pxe: FakePxe
    var stopped = false
    let (gate, release) = AsyncStream<Void>.makeStream()

    init(pxe: FakePxe) { self.pxe = pxe }

    func launch(_ network: NetworkInfo) async throws -> RunningPxe {
        for await _ in gate { break }
        return Stoppable(client: PxeClient(caller: pxe), pxe: pxe, launcher: self)
    }

    struct Stoppable: RunningPxe {
        let client: PxeClient
        let pxe: FakePxe
        let launcher: SlowLauncher
        func events() async -> AsyncStream<PxeEvent> { pxe.events }
        func stop() async { launcher.stopped = true }
    }
}

struct FakeBackend: WalletBackend {
    func networks() -> [NetworkInfo] { [] }
    func defaultNetworkId() -> String { "testnet-v6" }
    func checkNode(networkId: String) throws -> NodeCheck { NodeCheck(nodeVersion: "x", rollupVersion: 0, compatible: true, mismatches: []) }
    func pxeOpenParams(networkId: String) throws -> String { #"{"id":"\#(networkId)"}"# }
    func prepareProver(networkId: String) throws {}
    func prove(networkId: String, request: Data) throws -> Data { request }
    func newRecoveryPhrase() throws -> String { TestPhrases.phrase }
    func normalizeRecoveryPhrase(_ phrase: String) throws -> String { try TestPhrases.normalize(phrase) }
    func accountSigningKey(phrase: String, index: Int) throws -> String { TestPhrases.key(index) }
}

/// Recovery phrases for fake backends: any 24 words, and account i's key is i + 1 (the real scheme is Rust's).
enum TestPhrases {
    static let phrase = (Array(repeating: "abandon", count: 23) + ["art"]).joined(separator: " ")

    static func normalize(_ phrase: String) throws -> String {
        let words = phrase.lowercased().split(whereSeparator: \.isWhitespace)
        guard words.count == 24 else { throw VaultError("this is not a valid recovery phrase: 24 words expected") }
        return words.joined(separator: " ")
    }

    static func key(_ index: Int) -> String { "0x" + String(repeating: "0", count: 64 - String(index + 1, radix: 16).count) + String(index + 1, radix: 16) }
}

@MainActor
struct SessionTests {
    let network = NetworkInfo(id: "testnet-v6", label: "Testnet", aztecVersion: "6.0.0", barretenbergVersion: "6.0.0", rpcHost: "node", pxeBundle: "v6", proverSupported: true)

    func session(_ pxe: FakePxe, dir: URL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)) throws -> WalletSession {
        let vault = Vault(storage: MemoryStorage())
        let contents = try vault.create(first: AccountSecrets(alias: "main", signingKey: "0x" + String(repeating: "ab", count: 32), salt: Vault.zeroSalt))
        return WalletSession(backend: FakeBackend(), launcher: FakeLauncher(pxe: pxe), vault: vault, contents: contents, storeDirectory: dir, network: network, gate: pxe.gate)
    }

    @Test func openRegistersAccountsAndSelectsTheFirst() async throws {
        let s = try session(FakePxe())
        await s.open()
        #expect(s.phase == .ready)
        #expect(s.check?.blockNumber == 10)
        #expect(s.selected == "addr-abab")
        #expect(s.account?.deployed == false)
    }

    @Test func openFailuresAreReported() async throws {
        let pxe = FakePxe()
        pxe.failOpen = "not compatible"
        let s = try session(pxe)
        await s.open()
        #expect(s.phase == .failed)
        #expect(s.error == "open: not compatible")
    }

    @Test func tokensAndBalancesAreFollowedOnceDeployed() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try session(pxe)
        await s.open()
        try await s.addToken("0xt")
        #expect(s.tokens.first?.info.symbol == "TST")
        #expect(s.tokens.first?.total == "2500000")
    }

    @Test func aCancelledTransactionIsNeverSent() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try session(pxe)
        await s.open()
        pxe.whileProving = { pxe.gate.cancel() }
        let token = TokenInfo(address: "0xt", name: "Test", symbol: "TST", decimals: 6)
        await #expect(throws: CancelledByUser.self) { try await s.send(.private, token: token, to: "0x" + String(repeating: "cd", count: 32), amount: "1") }
        #expect(pxe.sent.isEmpty)
        #expect(s.activity.first?.error == "Cancelled")
        #expect(!pxe.gate.cancelled)
    }

    @Test func cancellingReleasesTheScreenWithoutWaitingForTheProof() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try session(pxe)
        await s.open()
        let (proof, release) = AsyncStream<Void>.makeStream()
        pxe.whileProving = { await s.cancelProving(); for await _ in proof { break } }
        let token = TokenInfo(address: "0xt", name: "Test", symbol: "TST", decimals: 6)
        await #expect(throws: CancelledByUser.self) { try await s.send(.private, token: token, to: "0x" + String(repeating: "cd", count: 32), amount: "1") }
        #expect(s.proving == nil)
        #expect(s.activity.first?.error == "Cancelled")
        #expect(s.winding != nil)
        #expect(pxe.gate.cancelled)
        release.yield()
        await s.winding?.value
        #expect(pxe.sent.isEmpty)
        #expect(!pxe.gate.cancelled)
    }

    @Test func aNetworkResetSetsTheOldChainsTokensAndHistoryAside() async throws {
        let pxe = FakePxe()
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let first = try session(pxe, dir: dir)
        await first.open()
        try await first.addToken("0xpal")
        await first.close()
        pxe.rollup = 3
        let s = try session(pxe, dir: dir)
        await s.open()
        #expect(s.chainReset)
        #expect(!s.tokens.contains { $0.info.address == "0xpal" })
        #expect(FileManager.default.fileExists(atPath: dir.appendingPathComponent("wallet-testnet-v6-1-2.json").path))
        s.acknowledgeChainReset()
        #expect(!s.chainReset)
    }

    @Test func theSameChainKeepsEverything() async throws {
        let pxe = FakePxe()
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let first = try session(pxe, dir: dir)
        await first.open()
        try await first.addToken("0xpal")
        await first.close()
        let s = try session(pxe, dir: dir)
        await s.open()
        #expect(!s.chainReset)
        #expect(s.tokens.contains { $0.info.address == "0xpal" })
    }

    @Test func withoutASponsorTheAccountsFeeJuiceIsRead() async throws {
        let pxe = FakePxe()
        pxe.fees = "feeJuice"
        let s = try session(pxe)
        await s.open()
        #expect(s.feeJuice == "1500000000000000000")
        #expect(Fees.line(s.check, feeJuice: s.feeJuice) == "Paid with your Fee Juice (you hold 1.5 FJ)")
    }

    @Test func feeJuiceIsReadOnSponsoredNetworksToo() async throws {
        let pxe = FakePxe()
        let s = try session(pxe)
        await s.open()
        #expect(s.feeJuice == "1500000000000000000")
        #expect(Fees.line(s.check, feeJuice: s.feeJuice) == Fees.sponsored)
    }

    @Test func anAccountIsActivatedOnce() async throws {
        let pxe = FakePxe()
        // Pending until the end: the fake node would otherwise confirm it in the background before the checks.
        pxe.holdReceipts = true
        defer { pxe.releaseReceipts.finish() }
        let s = try session(pxe)
        await s.open()
        try await s.deployAccount()
        #expect(s.activating)
        await #expect(throws: PxeError.self) { try await s.deployAccount() }
        #expect(pxe.calls.filter { $0.0 == "deployAccount" }.count == 1)
    }

    @Test func noWalletTransactionStartsWhileADappOneIsProven() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try session(pxe)
        await s.open()
        s.record(ActivityItem(account: s.selected!, kind: .dapp, status: .proving))
        let token = TokenInfo(address: "0xt", name: "Test", symbol: "TST", decimals: 6)
        await #expect(throws: PxeError.self) { try await s.send(.private, token: token, to: "0x" + String(repeating: "cd", count: 32), amount: "1") }
        #expect(pxe.sent.isEmpty)
    }

    @Test func goingToTheBackgroundFlushesThePxeStore() async throws {
        let pxe = FakePxe()
        let s = try session(pxe)
        await s.open()
        await s.flush()
        #expect(pxe.calls.last?.0 == "flush")
    }

    @Test func aHostThatStartsAfterCloseIsStopped() async throws {
        let pxe = FakePxe()
        let launcher = SlowLauncher(pxe: pxe)
        let vault = Vault(storage: MemoryStorage())
        let contents = try vault.create(first: AccountSecrets(alias: "main", signingKey: "0x" + String(repeating: "ab", count: 32), salt: Vault.zeroSalt))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let s = WalletSession(backend: FakeBackend(), launcher: launcher, vault: vault, contents: contents, storeDirectory: dir, network: network, gate: pxe.gate)
        let opening = Task { await s.open() }
        try await Task.sleep(for: .milliseconds(20))
        await s.close() // locked while the PXE host starts
        launcher.release.yield()
        await opening.value
        #expect(launcher.stopped)
        #expect(pxe.calls.isEmpty)
    }

    @Test func aRefreshOfThePreviousAccountDoesNotShowUnderTheNewOne() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try session(pxe)
        await s.open()
        try await s.addToken("0xt")
        try await s.addAccount(alias: "second", importedKey: "0x" + String(repeating: "12", count: 32))
        await s.select("addr-abab")
        pxe.holdBalances = true
        let running = Task { await s.refresh() }
        try await Task.sleep(for: .milliseconds(50)) // the first account's refresh waits for its balances
        await s.select("addr-1212") // queued behind it, not dropped
        pxe.release.yield()
        await running.value
        #expect(s.selected == "addr-1212")
        #expect(s.tokens.first?.total == "7000000")
    }

    @Test func transfersAreTrackedAndShieldingTargetsTheSender() async throws {
        let pxe = FakePxe()
        pxe.deployed = true
        let s = try session(pxe)
        await s.open()
        let token = TokenInfo(address: "0xt", name: "Test", symbol: "TST", decimals: 6)
        try await s.send(.shield, token: token, to: "ignored", amount: "10")
        #expect(pxe.sent.last?["to"] == .string("addr-abab"))
        #expect(s.proving == nil)
        // Confirmed in the background: waited for, not for a fixed time.
        await eventually { s.activity.first?.status == .confirmed }
        #expect(s.activity.first?.status == .confirmed)
        #expect(s.activity.first?.blockNumber == 11)
    }
}
