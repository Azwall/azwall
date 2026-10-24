package dev.azwall.app

import dev.azwall.app.ui.components.badgeHue
import dev.azwall.core.wallet.FeeJuice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeTest {
    @Test
    fun everyTokenGetsAHueEvenWithoutAnAddress() {
        assertEquals((0xabcdef % 360).toFloat(), badgeHue("0x12abcdef"))
        assertEquals(0f, badgeHue("0x"))
        assertTrue(badgeHue(FeeJuice.ID) in 0f..359f)
    }
}
