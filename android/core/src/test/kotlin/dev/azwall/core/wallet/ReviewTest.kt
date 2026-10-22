package dev.azwall.core.wallet

import dev.azwall.core.pxe.TransferKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTest {
    private val to = "0x" + "cd".repeat(32)

    @Test
    fun aSendListsTheWholeRecipientAndWhatStaysPrivate() {
        val review = Review.transfer(TransferKind.PRIVATE, "12.5 TST", to, "Testnet")
        assertEquals("Send privately", review.title)
        assertEquals(to, review.lines.toMap()["To"])
        assertEquals("12.5 TST", review.lines.toMap()["Amount"])
        assertTrue(review.lines.toMap()["Privacy"]!!.contains("hidden"))
        assertEquals("Visible to everyone on chain", Review.transfer(TransferKind.PUBLIC, "1 TST", to, "Testnet").lines.toMap()["Privacy"])
    }

    @Test
    fun shieldingHasNoRecipient() {
        val review = Review.transfer(TransferKind.SHIELD, "3 TST", to, "Testnet")
        assertEquals(null, review.lines.toMap()["To"])
        assertEquals("Public balance → private balance", review.lines.toMap()["Moves"])
    }

    @Test
    fun aMintWithoutRecipientGoesToTheAccount() {
        assertEquals("This account", Review.mint("1,000 TST", true, "", "Testnet").lines.toMap()["To"])
        assertEquals(to, Review.mint("1,000 TST", false, to, "Testnet").lines.toMap()["To"])
    }
}
