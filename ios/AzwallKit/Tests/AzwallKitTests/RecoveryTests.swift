import Foundation
import Testing

@testable import AzwallKit

struct RecoveryTests {
    let backend = FakeBackend()
    let key = "0x" + String(repeating: "12", count: 32)
    let db = "0x" + String(repeating: "ab", count: 32)

    @Test func aPhraseStartsAtAccountZeroAndIsKept() throws {
        let (account, phrase) = try Recovery.firstAccount("  \(TestPhrases.phrase.uppercased())\n", alias: " ", backend: backend)
        #expect(phrase == TestPhrases.phrase)
        #expect(account.signingKey == TestPhrases.key(0))
        #expect(account.index == 0)
        #expect(account.alias == "Account 1")
    }

    @Test func aKeyStartsAWalletWithoutPhrase() throws {
        let (account, phrase) = try Recovery.firstAccount(String(key.dropFirst(2)), alias: "Main", backend: backend)
        #expect(phrase == nil)
        #expect(account.signingKey == key)
        #expect(account.index == nil)
        #expect(Recovery.isSigningKey(key) && !Recovery.isSigningKey(TestPhrases.phrase))
    }

    @Test func newAccountsFollowThePhraseInOrder() throws {
        let first = try Recovery.firstAccount(TestPhrases.phrase, alias: "", backend: backend).0
        let imported = try Recovery.nextAccount(VaultContents(accounts: [], dbKey: db), alias: "x", importedKey: key, backend: backend)
        let contents = VaultContents(accounts: [first, imported], dbKey: db, phrase: TestPhrases.phrase)
        // The imported key takes no index: the next one of the phrase is 1.
        let next = try Recovery.nextAccount(contents, alias: "", importedKey: nil, backend: backend)
        #expect(next.index == 1)
        #expect(next.signingKey == TestPhrases.key(1))
        #expect(next.alias == "Account 3")
    }

    @Test func walletsWithoutPhraseGetRandomKeys() throws {
        let legacy = VaultContents(accounts: [try Recovery.firstAccount(key, alias: "", backend: backend).0], dbKey: db)
        let next = try Recovery.nextAccount(legacy, alias: "", importedKey: nil, backend: backend)
        #expect(next.index == nil)
        #expect(next.signingKey != key)
        #expect(try Vault.parseSigningKey(next.signingKey) == next.signingKey)
    }

    @Test func theVaultKeepsThePhrase() throws {
        let vault = Vault(storage: MemoryStorage())
        let (account, phrase) = try Recovery.firstAccount(TestPhrases.phrase, alias: "", backend: backend)
        _ = try vault.create(first: account, phrase: phrase)
        let opened = try vault.unlock()
        #expect(opened.phrase == TestPhrases.phrase)
        #expect(opened.accounts.first?.index == 0)
        #expect(opened.nextIndex == 1)
    }
}
