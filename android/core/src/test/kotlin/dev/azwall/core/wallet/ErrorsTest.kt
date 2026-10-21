package dev.azwall.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorsTest {
    @Test
    fun keepsTheFirstLineWithoutTheBridgePrefix() {
        assertEquals("insufficient balance", Errors.friendly("transfer: insufficient balance\n  at stack"))
        assertEquals("node https://rpc.io/… is down", Errors.friendly("open: node https://rpc.io/… is down"))
        assertEquals("Timeout after 30 s", Errors.friendly("Timeout after 30 s\nmore"))
        assertEquals("Something went wrong", Errors.friendly(null))
        assertEquals("Something went wrong", Errors.friendly("\nstack only"))
    }
}
