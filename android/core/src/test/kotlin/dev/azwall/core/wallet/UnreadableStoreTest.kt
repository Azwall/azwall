package dev.azwall.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnreadableStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun anUnreadableStoreStartsOverKeepsTheCopyAsideAndSaysSo() {
        tmp.root.resolve("wallet-net.json").writeText("not json")
        val store = WalletStore(tmp.root, "net")
        assertTrue(store.startedOver)
        assertTrue(store.tokens.isEmpty())
        // Set aside rather than overwritten by the next save: what it holds can still be looked at.
        assertEquals("not json", tmp.root.resolve("wallet-net.json.unreadable").readText())
        assertFalse(WalletStore(tmp.root, "net").startedOver)
    }

    @Test
    fun aFirstOpeningIsNotAProblem() {
        assertFalse(WalletStore(tmp.root, "fresh").startedOver)
    }
}
