package dev.azwall.core.wallet

import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.pxe.TokenMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTest {
    private val me = "0xme"
    private val pusd = TokenInfo("0xpusd", "Palmyr USD", "PUSD", 6)
    private fun move(from: String, to: String, tx: String = "0xtx", amount: String = "100") = TokenMove("0xPUSD", tx, 42, 1_000, from, to, amount)

    @Test
    fun transfersBecomeItemsWithTheirBlock() {
        val items = History.items(me, listOf(move("0xalice", me, "0x1"), move(me, "0xbob", "0x2"), move("0x00", me, "0x3")), listOf(pusd), emptyList())
        assertEquals(listOf(ActivityKind.RECEIVE, ActivityKind.SEND_PRIVATE, ActivityKind.MINT), items.map { it.kind })
        val received = items.first()
        assertEquals("0xalice", received.counterparty)
        assertEquals(42L, received.blockNumber)
        assertEquals("PUSD", received.symbol)
        assertEquals(ActivityStatus.CONFIRMED, received.status)
        assertNull(items.last().counterparty)
    }

    @Test
    fun recordedTransactionsAndChangeAreSkipped() {
        val recorded = ActivityItem("a", me, ActivityKind.DAPP, ActivityStatus.CONFIRMED, 1, txHash = "0xTX")
        assertEquals(emptyList<ActivityItem>(), History.items(me, listOf(move("0xalice", me)), listOf(pusd), listOf(recorded)))
        assertEquals(emptyList<ActivityItem>(), History.items(me, listOf(move(me, me, "0x9")), listOf(pusd), emptyList()))
    }

    @Test
    fun aTransferBetweenTwoOwnAccountsShowsOnBoth() {
        val transfer = move(me, "0xother")
        val sent = History.items(me, listOf(transfer), listOf(pusd), emptyList()).single()
        val received = History.items("0xother", listOf(transfer), listOf(pusd), emptyList()).single()
        assertEquals(ActivityKind.SEND_PRIVATE, sent.kind)
        assertEquals(ActivityKind.RECEIVE, received.kind)
        // The store keeps one item per id across accounts: each side needs its own.
        assertNotEquals(sent.id, received.id)
    }
}
