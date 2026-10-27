import AzwallCoreFFI
import AzwallKit
import Foundation

/// `WalletBackend` over the Rust core (uniffi bindings).
public struct RustWalletBackend: WalletBackend {
    private let core: WalletCore

    public init(configJson: String, nativeLibDir: String, crsDir: String, proverThreads: UInt32 = 0) throws {
        core = try rust { try WalletCore(configJson: configJson, nativeLibDir: nativeLibDir, crsDir: crsDir, proverThreads: proverThreads) }
    }

    public func networks() -> [NetworkInfo] {
        core.networks().map(NetworkInfo.init)
    }

    public func defaultNetworkId() -> String {
        core.defaultNetworkId()
    }

    public func checkNode(networkId: String) throws -> NodeCheck {
        NodeCheck(try rust { try core.checkNode(networkId: networkId) })
    }

    public func pxeOpenParams(networkId: String) throws -> String {
        try rust { try core.pxeOpenParams(networkId: networkId) }
    }

    public func prepareProver(networkId: String) throws {
        try rust { try core.prepareProver(networkId: networkId) }
    }

    public func prove(networkId: String, request: Data) throws -> Data {
        try rust { try core.prove(networkId: networkId, request: request) }
    }

    public func newRecoveryPhrase() throws -> String {
        try rust { try AzwallCoreFFI.newRecoveryPhrase() }
    }

    public func normalizeRecoveryPhrase(_ phrase: String) throws -> String {
        try rust { try AzwallCoreFFI.normalizeRecoveryPhrase(phrase: phrase) }
    }

    public func accountSigningKey(phrase: String, index: Int) throws -> String {
        try rust { try AzwallCoreFFI.accountSigningKey(phrase: phrase, index: UInt32(index)) }
    }
}

private func rust<T>(_ body: () throws -> T) throws -> T {
    do {
        return try body()
    } catch let error as AzwallError {
        throw WalletError(message(of: error))
    }
}

func message(of error: AzwallError) -> String {
    switch error {
    case .Config(let detail), .Node(let detail), .Prover(let detail): detail
    case .UnknownNetwork(let id): "unknown network '\(id)'"
    }
}

extension NetworkInfo {
    init(_ network: Network) {
        self.init(
            id: network.id,
            label: network.label,
            aztecVersion: network.aztecVersion,
            barretenbergVersion: network.barretenbergVersion,
            rpcHost: hostOf(network.rpcUrl),
            pxeBundle: network.pxeBundle,
            proverSupported: network.proverSupported,
            knownTokens: network.knownTokens.map { TokenInfo(address: $0.address, name: $0.name, symbol: $0.symbol, decimals: Int($0.decimals)) },
            dapps: network.knownDapps.map { AzwallKit.KnownDapp(name: $0.name, url: $0.url, description: $0.description) },
            explorers: network.explorers.map { AzwallKit.KnownExplorer(name: $0.name, url: $0.url, txUrl: $0.txUrl) }
        )
    }
}

extension NodeCheck {
    init(_ status: NodeStatus) {
        self.init(nodeVersion: status.nodeVersion, rollupVersion: status.rollupVersion, compatible: status.compatible, mismatches: status.mismatches)
    }
}
