package dev.azwall.core.wallet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets the user cancel the transaction being proven. Barretenberg cannot be stopped mid-proof, but the PXE sends a
 * transaction only once the app returned its proof: the app's `prove` handler asks [check] before proving and
 * [release] after, so a cancelled transaction's proof is refused and the transaction is never sent. Once a proof is
 * released the transaction may reach the network at any moment: from then on it cannot be cancelled.
 */
object ProofGate {
    @Volatile
    var cancelled = false
        private set

    private val _released = MutableStateFlow(false)

    /** Whether the proof went to the PXE, which sends it: too late to cancel. */
    val released: StateFlow<Boolean> = _released.asStateFlow()

    /** Cancels the transaction being proven; false when its proof was already released, so it may be sent. */
    @Synchronized
    fun cancel(): Boolean {
        if (_released.value) return false
        cancelled = true
        return true
    }

    /** A new transaction starts, or the last one ended: proofs go through again. */
    @Synchronized
    fun reset() {
        cancelled = false
        _released.value = false
    }

    /** Throws [CancelledByUser] if the user cancelled the transaction being proven. */
    fun check() {
        if (cancelled) throw CancelledByUser()
    }

    /**
     * Hands the finished proof over, unless the user cancelled first: then [CancelledByUser], and nothing is sent. A proof
     * for a host already stopped ([hostAlive] false: one retired after leaving its transaction unanswered) is refused
     * too, whatever the gate says: it would be sent behind a transaction shown failed, and must not mark the current
     * transaction as sending.
     */
    @Synchronized
    fun release(hostAlive: Boolean = true) {
        if (!hostAlive) throw CancelledByUser()
        check()
        _released.value = true
    }
}

class CancelledByUser : Exception("You cancelled the transaction; nothing was sent.")
