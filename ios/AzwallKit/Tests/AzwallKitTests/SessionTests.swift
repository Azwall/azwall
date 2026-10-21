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

    func call(_ method: String, _ params: JSONValue) async throws -> JSONValue {
        func p(_ k: String) -> String { params[k]?.stringValue ?? "" }
        calls.append((method, params))
        switch method {
        case "open":
            if let failOpen { throw PxeError("open: \(failOpen)") }
            return try JSONValue.parse(#"{"nodeVersion":"6.0.0-rc.3","l1ChainId":1,"rollupVersion":2,"vkTreeRoot":"0x","protocolContractsHash":"0x","blockNumber":10}"#)
        case "addAccount": return .object(["address": .string("addr-" + p("signingKey").suffix(4))])
        case "accountState": return .object(["deployed": .bool(deployed)])
        case "balances": return try JSONValue.parse(#"[{"token":"0xt","private":"2500000","public":"0"}]"#)
        case "tokenInfo": return .object(["address": .string(p("token")), "name": .string("Test"), "symbol": .string("TST"), "decimals": .number(6)])
        case "deployAccount", "transfer":
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

struct FakeBackend: WalletBackend {
    func networks() -> [NetworkInfo] { [] }
    func defaultNetworkId() -> String { "testnet-v6" }
    func checkNode(networkId: String) throws -> NodeCheck { NodeCheck(nodeVersion: "x", rollupVersion: 0, compatible: true, mismatches: []) }
    func pxeOpenParams(networkId: String) throws -> String { #"{"id":"\#(networkId)"}"# }
    func prepareProver(networkId: String) throws {}
    func prove(networkId: String, request: Data) throws -> Data { request }
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
