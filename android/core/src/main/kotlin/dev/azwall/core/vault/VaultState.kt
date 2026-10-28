package dev.azwall.core.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The unlocked vault, owned once for the whole app: every session reads it here (none keeps a copy) and every
 * change to it goes through [change], one at a time. A session started while another is saving (Retry, another
 * network) thus sees what that save wrote, and its own next save starts from it; unlocking waits for a save still
 * running, so it reads the vault that save left.
 */
class VaultState(private val vault: Vault) {
    private val lock = Mutex()

    @Volatile
    private var held: VaultContents? = null

    /** Counts locks: a save that ends after a lock writes the vault, but does not make it unlocked again. */
    @Volatile
    private var epoch = 0

    /** The vault's contents; throws while the wallet is locked. */
    val contents: VaultContents get() = held ?: error("the wallet is locked")

    /** The contents, or null while the wallet is locked. */
    val current: VaultContents? get() = held

    /** Unlocks (or creates) the vault with [read], after any save still running. */
    suspend fun open(read: suspend () -> VaultContents): VaultContents = lock.withLock {
        read().also { held = it }
    }

    /** The wallet locks: the contents leave memory. A save under way still lands on disk. */
    fun forget() {
        epoch++
        held = null
    }

    /**
     * Changes the vault from its latest contents, off the main thread (sealing may wait for the user to
     * authenticate): [write] saves the next contents with [vault] and returns them with its own result.
     */
    suspend fun <R> change(write: (Vault, VaultContents) -> Pair<VaultContents, R>): R = lock.withLock {
        val since = epoch
        val (next, result) = withContext(Dispatchers.IO) { write(vault, contents) }
        if (since == epoch) held = next
        result
    }
}
