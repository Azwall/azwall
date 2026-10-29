package dev.azwall.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun thePrivacyPolicyTheAppLinksToIsTheOnePublishedWithTheDocs() {
        // docs/privacy.md, published at azwall.github.io/azwall: the address Play Console gets too.
        assertEquals("https://azwall.github.io/azwall/privacy", Browsing.PRIVACY_POLICY)
        assertTrue(java.io.File("../../docs/privacy.md").exists())
    }

    /** 2026-10-28 store-release audit (F9): a long phishing host must not read as the site it imitates. */
    @Test
    fun anApprovalNamesTheSiteByTheDomainItsHostCannotFake() {
        assertEquals("evil-host.io", SiteName.domainOf("https://app.aztec.network.wallet-session-verify.evil-host.io"))
        assertEquals("aztec.network", SiteName.domainOf("https://app.aztec.network"))
        assertEquals("palmyr.io", SiteName.domainOf("https://aztec.palmyr.io:8443/swap"))
        assertEquals("example.co.uk", SiteName.domainOf("https://www.example.co.uk"))
        assertEquals("192.168.1.10", SiteName.domainOf("http://192.168.1.10:3000"))
        assertEquals("localhost", SiteName.domainOf("http://localhost:5173"))
        assertEquals("aztec.palmyr.io:8443", SiteName.hostOf("https://aztec.palmyr.io:8443/swap"))
        // Re-audit (R3): shared hosting keeps the tenant, and an IPv6 address stays whole.
        assertEquals("evil.github.io", SiteName.domainOf("https://evil.github.io/azwall"))
        assertEquals("azwall.github.io", SiteName.domainOf("https://azwall.github.io"))
        assertEquals("app.vercel.app", SiteName.domainOf("https://preview.app.vercel.app"))
        assertEquals("[::1]", SiteName.domainOf("http://[::1]:8080"))
    }
}
