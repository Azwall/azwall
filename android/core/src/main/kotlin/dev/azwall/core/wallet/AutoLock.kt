package dev.azwall.core.wallet

/**
 * When the wallet locks by itself: after [timeoutMs] in the background, checked when the app comes back. Locking
 * drops the keys from memory, so a phone picked up unlocked does not open a ready-to-sign wallet.
 */
class AutoLock(private val timeoutMs: Long = DEFAULT_MS, private val now: () -> Long = System::currentTimeMillis) {
    private var since: Long? = null

    fun backgrounded() {
        since = now()
    }

    /** True if the app was away long enough that the wallet should lock now. */
    fun foregrounded(): Boolean {
        val left = since ?: return false
        since = null
        return now() - left >= timeoutMs
    }

    companion object {
        const val DEFAULT_MS = 5 * 60_000L

        /** The longest the background lock waits for a transaction on its way before locking anyway. */
        const val MAX_SEND_WAIT_MS = 10 * 60_000L
    }
}
