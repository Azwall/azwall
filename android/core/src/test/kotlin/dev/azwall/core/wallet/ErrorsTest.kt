package dev.azwall.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ErrorsTest {
    @Test
    fun keepsTheFirstLineWithoutTheBridgePrefix() {
        assertEquals("insufficient balance", Errors.friendly("transfer: insufficient balance\n  at stack"))
        assertEquals("node https://rpc.io/… is down", Errors.friendly("open: node https://rpc.io/… is down"))
        assertEquals("Timeout after 30 s", Errors.friendly("Timeout after 30 s\nmore"))
        assertEquals("this is not a valid recovery phrase", Errors.friendly("detail=this is not a valid recovery phrase"))
        assertEquals("Something went wrong", Errors.friendly(null))
        assertEquals("Something went wrong", Errors.friendly("\nstack only"))
        // A dropped connection says what happened and what to do, not WebKit's or Chromium's words.
        assertEquals(Errors.NETWORK, Errors.friendly("claimFeeJuice: Load failed"))
        assertEquals(Errors.NETWORK, Errors.friendly("transfer: Failed to fetch"))
        // The node may have received it before the connection dropped: never tell the user nothing was sent.
        assertFalse("nothing was sent" in Errors.NETWORK)
    }
}
