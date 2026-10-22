package dev.azwall.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityDetailsTest {
    private val to = "0x" + "cd".repeat(32)
    private val hash = "0x" + "ef".repeat(32)
    private val sent = ActivityItem(
        id = "1", account = "0xme", kind = ActivityKind.SEND_PRIVATE, status = ActivityStatus.CONFIRMED, createdAt = 0,
        txHash = hash, token = "0xt", symbol = "TST", decimals = 6, amount = "12500001", counterparty = to, blockNumber = 42,
    )

    @Test
    fun aSentTransferShowsItsExactAmountRecipientHashAndBlock() {
        val lines = ActivityDetails.of(sent, "21 Oct 2026, 20:30").toMap()
        assertEquals("Confirmed in block 42", lines["Status"])
        assertEquals("12.500001 TST", lines["Amount"])
        assertEquals(to, lines["To"])
        assertEquals(hash, lines["Transaction hash"])
        assertEquals("21 Oct 2026, 20:30", lines["Date"])
        assertNull(lines["Error"])
    }

    @Test
    fun aFailedOrReceivedOneSaysSo() {
        val failed = ActivityDetails.of(sent.copy(status = ActivityStatus.FAILED, error = "Cancelled"), "d").toMap()
        assertEquals("Failed", failed["Status"])
        assertEquals("Cancelled", failed["Error"])
        assertEquals(to, ActivityDetails.of(sent.copy(kind = ActivityKind.RECEIVE), "d").toMap()["From"])
    }
}
