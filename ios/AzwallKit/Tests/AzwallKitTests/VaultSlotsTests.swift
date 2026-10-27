import Foundation
import Testing

@testable import AzwallKit

/// Keychain-like slots: adding to a taken slot fails, and the nth add can be made to fail (the phone locked).
final class FakeSlots: VaultSlots, @unchecked Sendable {
    var items: [String: Data] = [:]
    var failAdd: Set<Int> = []
    private var adds = 0

    func has(_ slot: String) -> Bool { items[slot] != nil }
    func read(_ slot: String) throws -> Data? { items[slot] }
    func add(_ slot: String, _ data: Data) throws {
        adds += 1
        if failAdd.contains(adds) { throw VaultError("interaction not allowed") }
        guard items[slot] == nil else { throw VaultError("duplicate item") }
        items[slot] = data
    }
    func remove(_ slot: String) throws { items[slot] = nil }
}

struct SlottedVaultStorageTests {
    let old = Data("old".utf8), new = Data("new".utf8)

    @Test func writesReplaceTheVaultAndLeaveOneCopy() throws {
        let slots = FakeSlots()
        let storage = SlottedVaultStorage(slots: slots)
        try storage.write(old)
        try storage.write(new)
        #expect(try storage.read() == new)
        #expect(slots.items == ["vault": new])
    }

    @Test func aFailedWriteNeverLosesTheKeys() throws {
        // The spare copy cannot be added: the main copy is untouched.
        let first = FakeSlots()
        first.items["vault"] = old
        first.failAdd = [1]
        #expect(throws: VaultError.self) { try SlottedVaultStorage(slots: first).write(new) }
        #expect(try SlottedVaultStorage(slots: first).read() == old)

        // The spare is written but the main copy cannot be re-added: the spare holds the new keys.
        let second = FakeSlots()
        second.items["vault"] = old
        second.failAdd = [2]
        let storage = SlottedVaultStorage(slots: second)
        #expect(throws: VaultError.self) { try storage.write(new) }
        #expect(storage.exists())
        #expect(try storage.read() == new)

        // The next write starts from that spare and ends with a single main copy.
        try storage.write(Data("newer".utf8))
        #expect(second.items == ["vault": Data("newer".utf8)])
    }

    @Test func deleteRemovesBothSlots() throws {
        let slots = FakeSlots()
        slots.items = ["vault": old, "vault.next": new]
        let storage = SlottedVaultStorage(slots: slots)
        try storage.delete()
        #expect(!storage.exists())
        #expect(throws: VaultError.self) { try storage.read() }
    }
}

struct AutoLockTests {
    final class Clock: @unchecked Sendable { var now = Date(timeIntervalSince1970: 0) }

    @Test func locksOnlyAfterTheTimeoutInTheBackground() {
        let clock = Clock()
        var lock = AutoLock(timeout: 1) { clock.now }
        let neverLeft = lock.foregrounded()
        lock.backgrounded()
        clock.now += 0.999
        let tooSoon = lock.foregrounded()
        lock.backgrounded()
        clock.now += 1
        let late = lock.foregrounded(), again = lock.foregrounded()
        #expect(!neverLeft && !tooSoon)
        #expect(late)
        #expect(!again, "counted once")
    }

    @Test func keysAreAsciiHexOnly() {
        // Fullwidth digits pass Character.isHexDigit: they would be stored, then refused by the PXE at every unlock.
        #expect(throws: VaultError.self) { try Vault.parseSigningKey(String(repeating: "１", count: 64)) }
        let key = try? Vault.parseSigningKey(" 0x" + String(repeating: "1", count: 64) + "\n")
        #expect(key == "0x" + String(repeating: "1", count: 64))
    }
}

struct ErrorsTests {
    @Test func keepsTheFirstLineWithoutTheBridgePrefix() {
        #expect(Errors.friendly("transfer: insufficient balance\n  at stack") == "insufficient balance")
        #expect(Errors.friendly("open: node https://rpc.io/… is down") == "node https://rpc.io/… is down")
        #expect(Errors.friendly("Timeout after 30 s\nmore") == "Timeout after 30 s")
        // A dropped connection says what happened and what to do, not WebKit's or Chromium's words.
        #expect(Errors.friendly("claimFeeJuice: Load failed") == Errors.network)
        #expect(Errors.friendly("transfer: Failed to fetch") == Errors.network)
        #expect(Errors.friendly(nil) == "Something went wrong")
        #expect(Errors.friendly("\nstack only") == "Something went wrong")
    }
}
