import Foundation
import Testing

@testable import AzwallKit

private final class FakeBackend: WalletBackend, @unchecked Sendable {
    private let lock = NSLock()
    private var _prepared: [String] = []
    let failNode: Bool

    init(failNode: Bool = false) {
        self.failNode = failNode
    }

    var prepared: [String] { lock.withLock { _prepared } }

    func networks() -> [NetworkInfo] {
        [network("testnet-v6", bb: "6.0.0-rc.6"), network("testnet-v5", bb: "5.1.0")]
    }

    func defaultNetworkId() -> String { "testnet-v6" }

    func checkNode(networkId: String) throws -> NodeCheck {
        if failNode { throw WalletError("connection refused") }
        return NodeCheck(nodeVersion: "6.0.0-rc.3", rollupVersion: 1, compatible: networkId == "testnet-v6", mismatches: [])
    }

    func prepareProver(networkId: String) throws {
        lock.withLock { _prepared.append(networkId) }
    }

    func prove(networkId: String, request: Data) throws -> Data { request }

    private func network(_ id: String, bb: String) -> NetworkInfo {
        NetworkInfo(id: id, label: id, aztecVersion: "x", barretenbergVersion: bb, rpcHost: "node.example", proverSupported: true)
    }
}

@MainActor
struct WalletModelTests {
    @Test func loadSelectsTheDefaultNetworkAndChecksItsNode() async {
        let model = WalletModel { FakeBackend() }
        await model.load()
        #expect(model.networks.count == 2)
        #expect(model.selectedId == "testnet-v6")
        #expect(model.node == .done(NodeCheck(nodeVersion: "6.0.0-rc.3", rollupVersion: 1, compatible: true, mismatches: [])))
    }

    @Test func nodeFailuresLandInTheState() async {
        let model = WalletModel { FakeBackend(failNode: true) }
        await model.load()
        #expect(model.node == .failed("connection refused"))
    }

    @Test func switchingNetworkResetsTheProver() async {
        let backend = FakeBackend()
        let model = WalletModel { backend }
        await model.load()
        await model.prepareProver()
        #expect(model.prover == .done("6.0.0-rc.6"))
        await model.select("testnet-v5")
        #expect(model.prover == .idle)
        await model.prepareProver()
        #expect(backend.prepared == ["testnet-v6", "testnet-v5"])
    }

    @Test func unknownNetworksAreIgnored() async {
        let model = WalletModel { FakeBackend() }
        await model.load()
        await model.select("mainnet")
        #expect(model.selectedId == "testnet-v6")
    }

    @Test func startupFailuresAreReported() async {
        let model = WalletModel { throw WalletError("missing azwall-config.json") }
        await model.load()
        #expect(model.startupError == "missing azwall-config.json")
    }

    @Test func hostOfHidesThePath() {
        #expect(hostOf("https://lb.drpc.org/aztec-testnet/SECRET") == "lb.drpc.org")
        #expect(hostOf("not a url") == "invalid URL")
    }
}
