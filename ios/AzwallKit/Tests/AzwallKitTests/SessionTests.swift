import Foundation
import Testing

@testable import AzwallKit

/// A PXE host in memory: addresses derive from keys, balances and deployment are settable.
final class FakePxe: PxeCaller, @unchecked Sendable {
    var deployed = false
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
    var whileProving: (() -> Void)?
    let (released, release) = AsyncStream<Void>.makeStream()

    func call(_ method: String, _ params: JSONValue) async throws -> JSONValue {
        func p(_ k: String) -> String { params[k]?.stringValue ?? "" }
        calls.append((method, params))
        switch method {
        case "open":
            if let failOpen { throw PxeError("open: \(failOpen)") }
            return try JSONValue.parse(#"{"nodeVersion":"6.0.0-rc.3","l1ChainId":1,"rollupVersion":2,"vkTreeRoot":"0x","protocolContractsHash":"0x","blockNumber":10}"#)
        case "addAccount": return .object(["address": .string("addr-" + p("signingKey").suffix(4))])
        case "accountState": return .object(["deployed": .bool(deployed)])
        case "balances":
            let first = p("owner") == "addr-abab"
            if first && holdBalances { for await _ in released { break } }
            return try JSONValue.parse(#"[{"token":"0xt","private":"\#(first ? "2500000" : "7000000")","public":"0"}]"#)
        case "tokenInfo": return .object(["address": .string(p("token")), "name": .string("Test"), "symbol": .string("TST"), "decimals": .number(6)])
        case "mint": return .object(["txHash": .string("0xmint")])
        case "deployAccount", "transfer":
            whileProving?()
            try ProofGate.shared.check()
            sent.append(params)
            return .object(["txHash": .string("0xhash\(sent.count)")])
        case "waitForTx":
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
    let network = NetworkInfo(id: "testnet-v6", label: "Testnet", aztecVersion: "6.0.0-rc.3", barretenbergVersion: "6.0.0-rc.6", rpcHost: "node", pxeBundle: "v6", proverSupported: true)

    func session(_ pxe: FakePxe, dir: URL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)) throws -> WalletSession {
        let vault = Vault(storage: MemoryStorage())
        let contents = try vault.create(first: AccountSecrets(alias: "main", signingKey: "0x" + String(repeating: "ab", count: 32), salt: Vault.zeroSalt))
        return WalletSession(backend: FakeBackend(), launcher: FakeLauncher(pxe: pxe), vault: vault, contents: contents, storeDirectory: dir, network: network)
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
        pxe.whileProving = { ProofGate.shared.cancel() }
        let token = TokenInfo(address: "0xt", name: "Test", symbol: "TST", decimals: 6)
        await #expect(throws: CancelledByUser.self) { try await s.send(.private, token: token, to: "0x" + String(repeating: "cd", count: 32), amount: "1") }
        #expect(pxe.sent.isEmpty)
        #expect(s.activity.first?.error == "Cancelled")
        #expect(!ProofGate.shared.cancelled)
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
        let s = WalletSession(backend: FakeBackend(), launcher: launcher, vault: vault, contents: contents, storeDirectory: dir, network: network)
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
        try await Task.sleep(for: .milliseconds(100))
        #expect(s.activity.first?.status == .confirmed)
        #expect(s.activity.first?.blockNumber == 11)
    }
}
