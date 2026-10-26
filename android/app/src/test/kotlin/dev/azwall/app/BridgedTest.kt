package dev.azwall.app

import dev.azwall.app.ui.home.Bridged
import dev.azwall.app.ui.home.bridged
import dev.azwall.core.NetworkInfo
import dev.azwall.core.pxe.FeeJuiceDeposit
import dev.azwall.core.wallet.AccountView
import dev.azwall.core.wallet.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgedTest {
    private val network = NetworkInfo("n", "N", "6", "6", "h", "v6", true)
    private fun deposit(amount: String, ready: Boolean, claimed: Boolean = false, ours: Boolean = true) =
        FeeJuiceDeposit(amount, "0xm", "1", ours = ours, ready = ready, claimed = claimed)
    private fun state(deployed: Boolean?, vararg d: FeeJuiceDeposit) =
        SessionState(network, accounts = listOf(AccountView("A", "0xa", "k", deployed)), selected = "0xa", deposits = d.toList())

    @Test
    fun onlyAnActiveAccountGetsAClaimButton() {
        val five = deposit("5000000000000000000", ready = true)
        assertEquals(Bridged("5 FJ bridged from Ethereum", claim = true), bridged(state(true, five)))
        // Not active: the activation claims it, so the line only says it is there.
        assertEquals(Bridged("5 FJ bridged from Ethereum", claim = false), bridged(state(false, five)))
    }

    @Test
    fun aDepositOnItsWayIsSaidAndAClaimedOneIsNot() {
        assertEquals(Bridged("2 FJ arriving from Ethereum", claim = false), bridged(state(true, deposit("2000000000000000000", ready = false))))
        assertNull(bridged(state(true, deposit("1", ready = true, claimed = true))))
    }

    @Test
    fun aDepositWithAnUnknownSecretAsksForIt() {
        val b = bridged(state(true, deposit("3000000000000000000", ready = false, ours = false)))!!
        assertEquals("3 FJ bridged with your own secret", b.text)
        assertEquals(true, b.askSecret)
        assertEquals(false, b.claim)
    }
}
