import Foundation
import Observation

public enum Status<Value: Equatable & Sendable>: Equatable, Sendable {
    case idle
    case running
    case done(Value)
    case failed(String)
}

/// Network selection, node check and prover warm-up. The backend is created lazily (it reads
/// the bundled config) and every call runs off the main actor, so failures only land in state.
@MainActor
@Observable
public final class WalletModel {
    public private(set) var networks: [NetworkInfo] = []
    public private(set) var selectedId: String?
    public private(set) var node: Status<NodeCheck> = .idle
    public private(set) var prover: Status<String> = .idle
    public private(set) var startupError: String?

    public var selected: NetworkInfo? { networks.first { $0.id == selectedId } }

    private let makeBackend: @Sendable () throws -> any WalletBackend
    private var backend: (any WalletBackend)?

    public init(backend: @escaping @Sendable () throws -> any WalletBackend) {
        makeBackend = backend
    }

    public func load() async {
        do {
            let backend = try await resolvedBackend()
            let (list, defaultId) = await Self.offMain { (backend.networks(), backend.defaultNetworkId()) }
            networks = list
            startupError = nil
            await select(defaultId)
        } catch {
            startupError = Self.describe(error)
        }
    }

    /// Selects a network and checks its node. A result for a network that is no longer
    /// selected is dropped.
    public func select(_ networkId: String) async {
        guard networks.contains(where: { $0.id == networkId }), let backend else { return }
        selectedId = networkId
        node = .running
        prover = .idle
        let result = await Self.offMain { Result { try backend.checkNode(networkId: networkId) } }
        guard selectedId == networkId else { return }
        node = Self.status(result)
    }

    public func prepareProver() async {
        guard let network = selected, let backend else { return }
        prover = .running
        let result = await Self.offMain {
            Result { try backend.prepareProver(networkId: network.id) }.map { network.barretenbergVersion }
        }
        if selectedId == network.id { prover = Self.status(result) }
    }

    private func resolvedBackend() async throws -> any WalletBackend {
        if let backend { return backend }
        let make = makeBackend
        let created = try await Self.offMain { Result { try make() } }.get()
        backend = created
        return created
    }

    private static func offMain<T: Sendable>(_ work: @escaping @Sendable () -> T) async -> T {
        await Task.detached(priority: .userInitiated, operation: work).value
    }

    private static func status<T: Equatable & Sendable>(_ result: Result<T, any Error>) -> Status<T> {
        switch result {
        case .success(let value): .done(value)
        case .failure(let error): .failed(describe(error))
        }
    }

    private static func describe(_ error: any Error) -> String {
        (error as? WalletError)?.message ?? error.localizedDescription
    }
}
