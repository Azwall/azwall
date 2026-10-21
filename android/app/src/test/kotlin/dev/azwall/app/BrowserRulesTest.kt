package dev.azwall.app

import dev.azwall.app.ui.browser.BrowserTabs.Companion.allowedOrigin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRulesTest {
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
