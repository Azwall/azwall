package dev.azwall.core.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLockTest {
    private var clock = 0L
    private val lock = AutoLock(timeoutMs = 1_000) { clock }

    @Test
    fun locksOnlyAfterTheTimeoutInTheBackground() {
        assertFalse("never left", lock.foregrounded())
        lock.backgrounded()
        clock = 999
        assertFalse(lock.foregrounded())
        lock.backgrounded()
        clock += 1_000
        assertTrue(lock.foregrounded())
        assertFalse("counted once", lock.foregrounded())
    }
}
