package dev.azwall.app

import dev.azwall.app.ui.statusText
import dev.azwall.core.NetworkInfo
import dev.azwall.core.pxe.SyncStatus
import dev.azwall.core.wallet.ActivityItem
import dev.azwall.core.wallet.ActivityKind
import dev.azwall.core.wallet.ActivityStatus
import dev.azwall.core.wallet.BackgroundTask
import dev.azwall.core.wallet.Phase
import dev.azwall.core.wallet.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusTextTest {
    private val ready = SessionState(NetworkInfo("n", "N", "6", "6", "h", "v6", true), phase = Phase.READY)
    private val now = 1_000_000_000_000L

    @Test
    fun idleShowsNothing() = assertNull(statusText(ready, now))

    @Test
    fun aLateSyncSaysHowFarBehind() {
        val from = SyncStatus(syncedBlock = 7_341, syncedAt = now / 1000 - 36 * 3600, tip = 8_581)
        assertEquals("Syncing PXE · 1,240 blocks behind (36 h)", statusText(ready.copy(task = BackgroundTask.Syncing(from)), now))
        assertEquals("Syncing PXE…", statusText(ready.copy(task = BackgroundTask.Syncing(from.copy(syncedBlock = 8_581))), now))
    }

    @Test
    fun pendingTransactionsAreCounted() {
        val item = ActivityItem("a", "0xme", ActivityKind.MINT, ActivityStatus.PENDING, 1)
        assertEquals("Waiting for 2 transactions to be mined…", statusText(ready.copy(activity = listOf(item, item.copy(id = "b"))), now))
        assertEquals("Looking for your tokens…", statusText(ready.copy(task = BackgroundTask.FindingTokens), now))
    }
}
