import Foundation

/// Lets the user cancel the transaction being proven. Barretenberg cannot be stopped mid-proof, but the PXE sends a
/// transaction only once the app returned its proof: the app's `prove` handler asks `check()` before and after
/// proving, so a cancelled transaction's proof is refused and the transaction is never sent.
public final class ProofGate: @unchecked Sendable {
    public static let shared = ProofGate()

    public init() {}
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

/// The first of two outcomes, awaited once: a transaction's result, or the user's cancel.
final class FirstOf<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var waiter: CheckedContinuation<Value, Error>?
    private var outcome: Result<Value, Error>?

    var finished: Bool { lock.withLock { outcome != nil } }

    /// Settles the outcome; later calls are ignored.
    func finish(_ result: Result<Value, Error>) {
        let waiter: CheckedContinuation<Value, Error>? = lock.withLock {
            guard outcome == nil else { return nil }
            outcome = result
            defer { self.waiter = nil }
            return self.waiter
        }
        waiter?.resume(with: result)
    }

    func value() async throws -> Value {
        try await withCheckedThrowingContinuation { continuation in
            let ready: Result<Value, Error>? = lock.withLock {
                if outcome == nil { waiter = continuation }
                return outcome
            }
            if let ready { continuation.resume(with: ready) }
        }
    }
}
