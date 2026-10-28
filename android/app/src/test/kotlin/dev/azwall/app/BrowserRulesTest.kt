package dev.azwall.app

import dev.azwall.app.ui.browser.BrowserTabs.Companion.allowedOrigin
import dev.azwall.app.ui.browser.BrowserTabs.Companion.originOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRulesTest {
    @Test
    fun onlySecureOriginsMayTalkToTheWallet() {
        assertTrue(allowedOrigin("https://vote.example.org"))
        assertTrue(allowedOrigin("http://localhost:5180", local = true))
        assertTrue(allowedOrigin("http://127.0.0.1:5180", local = true))
        // A release: any app on the phone could serve a "dApp" on this device.
        assertFalse(allowedOrigin("http://localhost:5180", local = false))
        assertFalse(allowedOrigin("http://vote.example.org"))
        assertFalse(allowedOrigin("file:///sdcard/x.html"))
        assertFalse(allowedOrigin("not an origin"))
    }

    @Test
    fun aPageUrlAndTheOriginItsMessagesCarryCompareEqual() {
        assertEquals("https://app.example", originOf("https://App.example/swap?x=1"))
        assertEquals(originOf("https://app.example:443/a"), originOf("https://app.example"))
        assertEquals("http://localhost:5180", originOf("http://localhost:5180/"))
        assertEquals(null, originOf("not a url"))
    }
}
