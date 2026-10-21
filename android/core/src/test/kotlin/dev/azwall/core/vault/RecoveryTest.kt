package dev.azwall.core.vault

import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.TestPhrases
import dev.azwall.core.WalletBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    private val backend = object : WalletBackend {
        override fun networks() = emptyList<NetworkInfo>()
        override fun defaultNetworkId() = "n"
        override fun checkNode(networkId: String) = NodeCheck("6", 0, true, emptyList())
        override fun pxeOpenParams(networkId: String) = "{}"
        override fun prepareProver(networkId: String) {}
        override fun prove(networkId: String, request: ByteArray) = request
        override fun newRecoveryPhrase() = TestPhrases.PHRASE
        override fun normalizeRecoveryPhrase(phrase: String) = TestPhrases.normalize(phrase)
        override fun accountSigningKey(phrase: String, index: Int) = TestPhrases.key(index)
    }
    private val key = "0x" + "12".repeat(32)

    @Test
    fun aPhraseStartsAtAccountZeroAndIsKept() {
        val (account, phrase) = Recovery.firstAccount("  ${TestPhrases.PHRASE.uppercase()}\n", " ", backend)
        assertEquals(TestPhrases.PHRASE, phrase)
        assertEquals(TestPhrases.key(0), account.signingKey)
        assertEquals(0, account.index)
        assertEquals("Account 1", account.alias)
    }

    @Test
    fun aKeyStartsAWalletWithoutPhrase() {
        val (account, phrase) = Recovery.firstAccount(key.removePrefix("0x"), "Main", backend)
        assertNull(phrase)
        assertEquals(key, account.signingKey)
        assertNull(account.index)
        assertTrue(Recovery.isSigningKey(key) && !Recovery.isSigningKey(TestPhrases.PHRASE))
    }

    @Test
    fun aKeyCopiedInGroupsOrWrappedIsTheSameKey() {
        val body = key.removePrefix("0x")
        val grouped = body.chunked(4).joinToString(" ")
        val wrapped = "  ${body.take(32)}\n${body.drop(32)}\n"
        assertEquals(key, Recovery.firstAccount(grouped, "", backend).first.signingKey)
        assertEquals(key, Recovery.firstAccount(wrapped, "", backend).first.signingKey)
    }

    @Test
    fun newAccountsFollowThePhraseInOrder() {
        val contents = VaultContents(
            accounts = listOf(Recovery.firstAccount(TestPhrases.PHRASE, "", backend).first, Recovery.nextAccount(VaultContents(accounts = emptyList(), dbKey = DB), "x", key, backend)),
            dbKey = DB,
            phrase = TestPhrases.PHRASE,
        )
        // The imported key takes no index: the next one of the phrase is 1.
        val next = Recovery.nextAccount(contents, "", null, backend)
        assertEquals(1, next.index)
        assertEquals(TestPhrases.key(1), next.signingKey)
        assertEquals("Account 3", next.alias)
    }

    @Test
    fun walletsWithoutPhraseGetRandomKeys() {
        val legacy = VaultContents(accounts = listOf(Recovery.firstAccount(key, "", backend).first), dbKey = DB)
        val next = Recovery.nextAccount(legacy, "", null, backend)
        assertNull(next.index)
        assertTrue(next.signingKey != key && Vault.parseSigningKey(next.signingKey) == next.signingKey)
    }

    private companion object {
        val DB = "0x" + "ab".repeat(32)
    }
}
