package dev.azwall.core.wallet

import dev.azwall.core.pxe.NetworkCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeesTest {
    private fun check(fees: String) = NetworkCheck("6.0.0", 1, 2, "0x", "0x", 10, fees)

    @Test
    fun sponsoredNetworksChargeNothing() {
        assertEquals(Fees.SPONSORED, Fees.line(check("sponsored"), null))
        assertEquals(Fees.SPONSORED, Fees.line(null, null))
    }

    @Test
    fun elsewhereTheAccountPaysWithItsFeeJuice() {
        assertEquals("Paid with your Fee Juice (you hold 1.5 FJ)", Fees.line(check("feeJuice"), "1500000000000000000"))
        assertTrue(Fees.line(check("feeJuice"), "0").contains("will fail"))
        assertTrue(Fees.activation(check("feeJuice"), "0").contains("bridge AZTEC from Ethereum"))
        assertTrue(Fees.activation(check("feeJuice"), "5").contains("paid with the Fee Juice it holds"))
    }
}
