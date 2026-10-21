import Foundation

/// Named copies of the sealed vault (Keychain items in the app, memory in tests).
public protocol VaultSlots: Sendable {
    /// Whether the slot holds a copy; never shows an authentication prompt.
    func has(_ slot: String) -> Bool
    /// The slot's copy, nil when the slot is empty; may show the system authentication prompt.
    func read(_ slot: String) throws -> Data?
    /// Stores a copy in an empty slot; fails if the slot is taken.
    func add(_ slot: String, _ data: Data) throws
    func remove(_ slot: String) throws
}

/// The vault over two slots, written so that some complete copy exists at every step: the Keychain cannot replace
/// a protected item in place, and deleting before adding would lose every key if the add then failed (the phone
/// locked meanwhile, for instance). The new copy goes to the spare slot first; only then is the main one replaced.
public struct SlottedVaultStorage: VaultStorage {
    static let main = "vault"
    static let spare = "vault.next"
    private let slots: VaultSlots

    public init(slots: VaultSlots) {
        self.slots = slots
    }

    public func exists() -> Bool { slots.has(Self.main) || slots.has(Self.spare) }

    /// The main copy, or the spare one when a write stopped after removing the main one.
    public func read() throws -> Data {
        if let data = try slots.read(Self.main) ?? slots.read(Self.spare) { return data }
        throw VaultError("no vault")
    }

    public func write(_ data: Data) throws {
        if slots.has(Self.main) {
            // A spare left by an interrupted write is older than the main copy: drop it.
            try? slots.remove(Self.spare)
            try slots.add(Self.spare, data)
            try slots.remove(Self.main)
        }
        // Without a main copy, the spare (if any) is the newest: it stays until the main one is written.
        try slots.add(Self.main, data)
        try? slots.remove(Self.spare)
    }

    public func delete() throws {
        try slots.remove(Self.main)
        try slots.remove(Self.spare)
    }
}
