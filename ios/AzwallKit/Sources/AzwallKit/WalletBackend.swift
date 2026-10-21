import Foundation

/// A network the app embeds, as the UI needs it.
public struct NetworkInfo: Equatable, Identifiable, Sendable {
    public let id: String
    public let label: String
    public let aztecVersion: String
    public let barretenbergVersion: String
    /// Host of the RPC endpoint only: the path may carry an API key.
    public let rpcHost: String
    /// PXE host bundle: `pxe/<pxeBundle>/index.html` in the app's resources.
    public let pxeBundle: String
    public let proverSupported: Bool
    /// Tokens followed from the start on this network (networks.json).
    public let knownTokens: [TokenInfo]
    /// dApps the browser's start page suggests on this network (networks.json).
    public let dapps: [KnownDapp]

    public init(
        id: String, label: String, aztecVersion: String, barretenbergVersion: String, rpcHost: String, pxeBundle: String, proverSupported: Bool,
        knownTokens: [TokenInfo] = [], dapps: [KnownDapp] = []
    ) {
        self.id = id
        self.label = label
        self.aztecVersion = aztecVersion
        self.barretenbergVersion = barretenbergVersion
        self.rpcHost = rpcHost
        self.pxeBundle = pxeBundle
        self.proverSupported = proverSupported
        self.knownTokens = knownTokens
        self.dapps = dapps
    }
}

public struct NodeCheck: Equatable, Sendable {
    public let nodeVersion: String
    public let rollupVersion: UInt64
    public let compatible: Bool
    public let mismatches: [String]

    public init(nodeVersion: String, rollupVersion: UInt64, compatible: Bool, mismatches: [String]) {
        self.nodeVersion = nodeVersion
        self.rollupVersion = rollupVersion
        self.compatible = compatible
        self.mismatches = mismatches
    }
}

public struct WalletError: Error, Equatable, LocalizedError {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var errorDescription: String? { message }
}

/// The wallet core as the app sees it. Every call blocks (network, proving): never call it on
/// the main actor. Failures are thrown as `WalletError`.
public protocol WalletBackend: Sendable {
    func networks() -> [NetworkInfo]
    func defaultNetworkId() -> String
    func checkNode(networkId: String) throws -> NodeCheck
    /// JSON `network` parameter of the PXE host's `open` call.
    func pxeOpenParams(networkId: String) throws -> String
    /// Loads the network's Barretenberg release ahead of the first proof.
    func prepareProver(networkId: String) throws
    /// Proves an AZS2 request from the network's PXE and returns the AZP1 response.
    func prove(networkId: String, request: Data) throws -> Data
}

/// The host of an RPC URL, never its path or query.
public func hostOf(_ url: String) -> String {
    guard let host = URLComponents(string: url)?.host, !host.isEmpty else { return "invalid URL" }
    return host
}
