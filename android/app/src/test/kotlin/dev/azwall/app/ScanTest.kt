package dev.azwall.app

import dev.azwall.app.ui.send.readScan
import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.wallet.TokenView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanTest {
    private val me = "0x" + "aa".repeat(32)
    private val usdc = TokenView(TokenInfo("0x" + "01".repeat(32), "USD Coin", "USDC", 6), null)
    private val weth = "0x" + "02".repeat(32)

    @Test
    fun aRequestForAFollowedTokenFillsItsAmount() {
        val scan = readScan("aztec:$me?network=n&token=${usdc.info.address}&amount=10", "n", listOf(usdc))
        assertEquals(0, scan.tokenIndex)
        assertEquals("10", scan.amount)
    }

    @Test
    fun anAmountForATokenNotInTheListIsNotFilledInForAnother() {
        val scan = readScan("aztec:$me?network=n&token=$weth&amount=10", "n", listOf(usdc))
        assertNull(scan.amount)
        assertEquals("The requested token is not in your list: add it first", scan.note)
    }
}
