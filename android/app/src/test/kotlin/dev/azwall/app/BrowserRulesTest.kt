package dev.azwall.app

import dev.azwall.app.ui.browser.BrowserController.Companion.allowedOrigin
import dev.azwall.app.ui.browser.BrowserController.Companion.toUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRulesTest {
    @Test
    fun addressBarInputBecomesAUrlOrASearch() {
        assertEquals("https://vote.example.org", toUrl(" vote.example.org "))
        assertEquals("https://x.dev/a?b=1", toUrl("https://x.dev/a?b=1"))
        assertEquals("http://localhost:5180", toUrl("localhost:5180"))
        assertEquals("http://127.0.0.1:5180", toUrl("127.0.0.1:5180"))
        assertEquals("https://www.google.com/search?q=aztec+voting+dapp", toUrl("aztec voting dapp"))
        assertEquals("https://www.google.com", toUrl(""))
    }

    @Test
    fun onlySecureOriginsMayTalkToTheWallet() {
        assertTrue(allowedOrigin("https://vote.example.org"))
        assertTrue(allowedOrigin("http://localhost:5180"))
        assertTrue(allowedOrigin("http://127.0.0.1:5180"))
        assertFalse(allowedOrigin("http://vote.example.org"))
        assertFalse(allowedOrigin("file:///sdcard/x.html"))
        assertFalse(allowedOrigin("not an origin"))
    }
}
