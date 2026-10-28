package dev.azwall.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowsingTest {
    @Test
    fun aBuildWithoutTheConnectionScriptSaysSoAndWhatToDo() {
        assertEquals(
            "Apps cannot connect to the wallet on Mainnet (v6): this version of Azwall lacks the connection script for it. Update the app.",
            Browsing.noConnectionScript("Mainnet (v6)"),
        )
    }

    @Test
    fun addressesOpenAsTypedAndTextIsSearchedPrivately() {
        val ddg = SearchEngine.DEFAULT
        assertEquals("https://vote.example.org", Browsing.addressOf(" vote.example.org ", ddg))
        assertEquals("https://x.dev/a?b=1", Browsing.addressOf("https://x.dev/a?b=1", ddg))
        assertEquals("http://localhost:5180", Browsing.addressOf("localhost:5180", ddg))
        assertEquals("http://127.0.0.1:5180", Browsing.addressOf("127.0.0.1:5180", ddg))
        assertEquals("https://duckduckgo.com/?q=aztec+voting+dapp", Browsing.addressOf("aztec voting dapp", ddg))
        assertEquals("https://search.brave.com/search?q=aztec", Browsing.addressOf("aztec", SearchEngine.BRAVE))
        assertNull(Browsing.addressOf("  ", ddg))
    }

    @Test
    fun aSavedEngineFallsBackToTheDefault() {
        assertEquals(SearchEngine.STARTPAGE, SearchEngine.named("STARTPAGE"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.named(null))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.named("GOOGLE"))
    }

    @Test
    fun aDappTileShowsItsHost() {
        assertEquals("aztec.palmyr.io", KnownDapp("Palmyr", "https://aztec.palmyr.io/swap?x=1").host)
        assertEquals("dapps/aztec.palmyr.io.png", KnownDapp("Palmyr", "https://aztec.palmyr.io/swap").iconPath)
        // The same on iOS (BrowsingTests): a dApp has one colour everywhere.
        assertEquals("ac675a", KnownDapp("Palmyr", "https://aztec.palmyr.io").tint)
    }

    @Test
    fun anExplorerLinksATransactionAndNothingElse() {
        val scan = KnownExplorer("Explorer", "https://explorer.example/?network=testnet", "https://explorer.example/tx/{hash}?network=testnet")
        assertEquals("https://explorer.example/tx/0x0a1B?network=testnet", scan.pageOfTx("0x0a1B"))
        listOf("0x", "0a1b", "0x12/../admin", "0x12?network=mainnet").forEach { assertNull(it, scan.pageOfTx(it)) }
        assertEquals("explorer.example", scan.tile.host)
    }
}
