package dev.azwall.core.wallet

/**
 * Lets the user cancel the transaction being proven. Barretenberg cannot be stopped mid-proof, but the PXE sends a
 * transaction only once the app returned its proof: the app's `prove` handler asks [check] before and after
 * proving, so a cancelled transaction's proof is refused and the transaction is never sent.
 */
object ProofGate {
    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    /** A new transaction starts, or the last one ended: proofs go through again. */
    fun reset() {
        cancelled = false
    }

    /** Throws [CancelledByUser] if the user cancelled the transaction being proven. */
    fun check() {
        if (cancelled) throw CancelledByUser()
    }
}

class CancelledByUser : Exception("You cancelled the transaction; nothing was sent.")
