import Foundation

/// Lets the user cancel the transaction being proven. Barretenberg cannot be stopped mid-proof, but the PXE sends a
/// transaction only once the app returned its proof: the app's `prove` handler asks `check()` before and after
/// proving, so a cancelled transaction's proof is refused and the transaction is never sent.
public final class ProofGate: @unchecked Sendable {
    public static let shared = ProofGate()
    private let lock = NSLock()
    private var flag = false

    public var cancelled: Bool { lock.withLock { flag } }

    public func cancel() { lock.withLock { flag = true } }

    /// A new transaction starts, or the last one ended: proofs go through again.
    public func reset() { lock.withLock { flag = false } }

    /// Throws `CancelledByUser` if the user cancelled the transaction being proven.
    public func check() throws {
        if cancelled { throw CancelledByUser() }
    }
}

public struct CancelledByUser: Error, LocalizedError {
    public init() {}
    public var errorDescription: String? { "You cancelled the transaction; nothing was sent." }
}
