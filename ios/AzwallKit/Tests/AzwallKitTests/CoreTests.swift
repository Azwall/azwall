import Foundation
import Testing

@testable import AzwallKit

final class MemoryStorage: VaultStorage, @unchecked Sendable {
    var data: Data?
    func exists() -> Bool { data != nil }
    func read() throws -> Data {
        guard let data else { throw VaultError("no vault") }
        return data
    }
    func write(_ data: Data) throws { self.data = data }
    func delete() throws { data = nil }
}

struct AmountsTests {
    @Test func formatsWithGroupingAndTrimmedDecimals() {
        #expect(Amounts.format("1234500000", decimals: 6) == "1,234.5")
        #expect(Amounts.format("0", decimals: 18) == "0")
        #expect(Amounts.format("1", decimals: 6) == "0.000001")
        #expect(Amounts.format("1234567", decimals: 6, maxFraction: 4) == "1.2345")
        #expect(Amounts.format("123456789012345678", decimals: 0) == "123,456,789,012,345,678")
    }

    @Test func dustNeverShowsAsZero() {
        // 0.0074 WETH split as 0.0070 private and 0.0004 public: the public part is dust at two decimals.
        #expect(Amounts.display("400000000000000", decimals: 18, maxFraction: 2) == "< 0.01")
        #expect(Amounts.display("400000000000000", decimals: 18, maxFraction: 4) == "0.0004")
        #expect(Amounts.display("0", decimals: 18, maxFraction: 2) == "0")
        #expect(Amounts.display("1500000", decimals: 6, maxFraction: 2) == "1.5")
    }

    @Test func parsesHumanInput() throws {
        #expect(try Amounts.parse("1.5", decimals: 6) == "1500000")
        #expect(try Amounts.parse(" 1,5 ", decimals: 6) == "1500000")
        #expect(try Amounts.parse("1.5\n", decimals: 6) == "1500000") // pasted with its line break, as on Android
        #expect(try Amounts.parse(".5", decimals: 6) == "500000")
        #expect(try Amounts.parse("1000000", decimals: 18) == "1" + String(repeating: "0", count: 24))
    }

    @Test(arguments: ["", "abc", "-1", "0", "0.0", "1.1234567", "1e5", "."])
    func rejectsInvalidAmounts(_ input: String) {
        #expect(throws: Amounts.InvalidAmount.self) { try Amounts.parse(input, decimals: 6) }
    }

    @Test func addsAndComparesBigNumbers() {
        #expect(Amounts.add("999999999999999999999", "1") == "1000000000000000000000")
        #expect(Amounts.compare("0010", "9") == .orderedDescending)
        #expect(Amounts.compare("123", "123") == .orderedSame)
    }
}

struct VaultTests {
    let main = AccountSecrets(alias: "main", signingKey: "0x" + String(repeating: "12", count: 32), salt: Vault.zeroSalt)

    @Test func createThenUnlockRoundTrips() throws {
        let vault = Vault(storage: MemoryStorage())
        let created = try vault.create(first: main)
        #expect(Vault.isHex32(created.dbKey))
        #expect(try vault.unlock() == created)
        #expect(throws: VaultError.self) { try vault.create(first: main) }
    }

    @Test func accountsAreAddedWithoutDuplicates() throws {
        let vault = Vault(storage: MemoryStorage())
        let second = AccountSecrets(alias: "savings", signingKey: "0x" + String(repeating: "23", count: 32), salt: Vault.zeroSalt)
        let contents = try vault.addAccount(try vault.create(first: main), second)
        #expect(try vault.unlock().accounts.map(\.alias) == ["main", "savings"])
        #expect(throws: VaultError.self) { try vault.addAccount(contents, second) }
        #expect(try vault.renameAccount(contents, signingKey: main.signingKey, alias: " daily ").accounts[0].alias == "daily")
    }

    @Test func signingKeysAreValidated() throws {
        #expect(try Vault.parseSigningKey("  " + String(repeating: "1A", count: 32)) == "0x" + String(repeating: "1a", count: 32))
        #expect(throws: VaultError.self) { try Vault.parseSigningKey("0x1234") }
        #expect(throws: VaultError.self) { try Vault.parseSigningKey(String(repeating: "ff", count: 32)) }
        #expect(throws: VaultError.self) { try Vault.parseSigningKey(String(repeating: "00", count: 32)) }
        let keys = (0..<50).map { _ in Vault.newSigningKey() }
        #expect(Set(keys).count == 50)
        #expect(keys.allSatisfy { (try? Vault.parseSigningKey($0)) == $0 })
    }
}

struct ChannelTests {
    final class Outbox: @unchecked Sendable {
        let lock = NSLock()
        var messages: [JSONValue] = []
        func add(_ json: String) { lock.withLock { messages.append(try! JSONValue.parse(json)) } }
        var all: [JSONValue] { lock.withLock { messages } }
    }

    @Test func callsResolveAndFailWithTheHostReply() async throws {
        let out = Outbox()
        let channel = PxeChannel(send: out.add, handlers: [:])
        async let block = channel.call("blockNumber")
        try await Task.sleep(for: .milliseconds(50))
        let id = out.all[0]["id"]!.intValue!
        await channel.receive(#"{"kind":"reply","id":\#(id),"ok":true,"result":7678}"#)
        #expect(try await block == .number(7678))
        let open = Task { try await channel.call("open") }
        try await Task.sleep(for: .milliseconds(50))
        await channel.receive(#"{"kind":"reply","id":\#(id + 1),"ok":false,"error":"not compatible"}"#)
        await #expect(throws: PxeError("open: not compatible")) { try await open.value }
    }

    @Test func hostCallsReachNativeHandlers() async throws {
        let out = Outbox()
        let channel = PxeChannel(send: out.add, handlers: ["prove": { p in .object(["response": .string("echo:" + (p["request"]?.stringValue ?? ""))]) }])
        await channel.receive(#"{"kind":"call","id":9,"method":"prove","params":{"request":"AA=="}}"#)
        await channel.receive(#"{"kind":"call","id":10,"method":"nope","params":{}}"#)
        try await Task.sleep(for: .milliseconds(100))
        let replies = Dictionary(uniqueKeysWithValues: out.all.map { ($0["id"]!.intValue!, $0) })
        #expect(replies[9]?["result"]?["response"] == .string("echo:AA=="))
        #expect(replies[10]?["ok"] == .bool(false))
    }

    @Test func closingFailsPendingCalls() async throws {
        let channel = PxeChannel(send: { _ in }, handlers: [:])
        let pending = Task { try await channel.call("balances") }
        try await Task.sleep(for: .milliseconds(50))
        await channel.close("web view destroyed")
        await #expect(throws: PxeError("balances: web view destroyed")) { try await pending.value }
        await #expect(throws: PxeError("blockNumber: web view destroyed")) { try await channel.call("blockNumber") }
    }

    @Test func booleansAndNumbersStayDistinct() throws {
        let v = try JSONValue.parse(#"{"a":true,"b":1,"c":0}"#)
        #expect(v["a"] == .bool(true))
        #expect(v["b"] == .number(1))
        #expect(v["c"]?.boolValue == nil)
    }
}
