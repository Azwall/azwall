import Foundation

/// The unlocked vault, owned once for the whole app: every session reads it here (none keeps a copy) and every change
/// to it goes through `change`, one at a time. A session started while another is saving (Retry, another network) thus
/// sees what that save wrote, and its own next save starts from it; unlocking waits for a save still running, so it
/// reads the vault that save left; deleting the wallet waits for it too, so it cannot write the vault back.
public final class VaultState: @unchecked Sendable {
    private let vault: Vault
    private let lock = NSLock()
    private var held: VaultContents?
    /// Counts locks: a save that ends after a lock writes the vault, but does not make it unlocked again.
    private var epoch = 0
    /// The last operation queued: the next one starts once it has ended.
    private var tail: Task<Void, Never>?

    /// `unlocked`: contents already read (a session built around them in tests); nil, the wallet starts locked.
    public init(vault: Vault, unlocked: VaultContents? = nil) {
        self.vault = vault
        held = unlocked
    }

    /// The contents, or nil while the wallet is locked.
    public var current: VaultContents? { lock.withLock { held } }

    /// The vault's contents; throws while the wallet is locked.
    public func contents() throws -> VaultContents {
        guard let held = current else { throw VaultError("the wallet is locked") }
        return held
    }

    /// Unlocks (or creates) the vault with `read`, after any save still running.
    public func open(_ read: @escaping @Sendable () async throws -> VaultContents) async throws -> VaultContents {
        try await serialized {
            let contents = try await read()
            self.lock.withLock { self.held = contents }
            return contents
        }
    }

    /// The wallet locks: the contents leave memory. A save under way still lands on disk.
    public func forget() {
        lock.withLock {
            epoch += 1
            held = nil
        }
    }

    /// Changes the vault from its latest contents, off the main actor (the Keychain may ask the user to
    /// authenticate): `write` saves the next contents with the vault and returns them with its own result.
    public func change<R: Sendable>(_ write: @escaping @Sendable (Vault, VaultContents) throws -> (VaultContents, R)) async throws -> R {
        try await serialized {
            let (since, contents) = self.lock.withLock { (self.epoch, self.held) }
            guard let contents else { throw VaultError("the wallet is locked") }
            let vault = self.vault
            let (next, result) = try await Task.detached { try write(vault, contents) }.value
            self.lock.withLock { if self.epoch == since { self.held = next } }
            return result
        }
    }

    /// Deletes the wallet with `erase`, once any save under way has landed. The wallet is locked first, so a change
    /// asked for later finds no vault to change.
    public func erase(_ erase: @escaping @Sendable () throws -> Void) async throws {
        forget()
        try await serialized { try await Task.detached { try erase() }.value }
    }

    /// Runs `body` once every operation queued before it has ended, whatever their outcome.
    private func serialized<T: Sendable>(_ body: @escaping @Sendable () async throws -> T) async throws -> T {
        let task: Task<T, Error> = lock.withLock {
            let before = tail
            let task = Task<T, Error> {
                await before?.value
                return try await body()
            }
            // Its caller gets its failure; the next operation only waits for it to end.
            tail = Task { _ = await task.result }
            return task
        }
        return try await task.value
    }
}
