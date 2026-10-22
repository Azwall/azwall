package dev.azwall.core.wallet

import dev.azwall.core.pxe.TransferKind

/**
 * What the confirmation screen lists before a transaction is proven and sent: every detail the user typed or chose,
 * the recipient in full, and what stays private. Nothing is proven or sent until the user confirms it.
 */
data class Review(val title: String, val lines: List<Pair<String, String>>, val confirm: String) {
    companion object {
        /** Fees are paid by the network's Sponsored FPC (wallet.ts): the user pays none. */
        const val FEE = "None, paid by the network's sponsor"

        /** A transfer of `amount` (already formatted, with its symbol); `to` is ignored when shielding. */
        fun transfer(kind: TransferKind, amount: String, to: String, network: String): Review = when (kind) {
            TransferKind.PRIVATE -> Review(
                "Send privately",
                listOf("Amount" to amount, "To" to to, "Privacy" to "Amount, sender and recipient stay hidden", "Network" to network, "Fee" to FEE),
                "Confirm and send",
            )
            TransferKind.PUBLIC -> Review(
                "Send publicly",
                listOf("Amount" to amount, "To" to to, "Privacy" to "Visible to everyone on chain", "Network" to network, "Fee" to FEE),
                "Confirm and send",
            )
            TransferKind.SHIELD -> Review(
                "Make private",
                listOf("Amount" to amount, "Moves" to "Public balance → private balance", "Privacy" to "The amount is visible once, then hidden", "Network" to network, "Fee" to FEE),
                "Confirm",
            )
            TransferKind.UNSHIELD -> Review(
                "Make public",
                listOf("Amount" to amount, "Moves" to "Private balance → public balance", "Privacy" to "The amount becomes visible to everyone", "Network" to network, "Fee" to FEE),
                "Confirm",
            )
        }

        /** Minting `amount` of the account's own token; an empty `to` mints to the account itself. */
        fun mint(amount: String, private: Boolean, to: String, network: String): Review = Review(
            if (private) "Mint privately" else "Mint publicly",
            listOf(
                "Amount" to amount,
                "To" to to.ifEmpty { "This account" },
                "Privacy" to if (private) "A note only the recipient can see" else "Visible to everyone on chain",
                "Network" to network,
                "Fee" to FEE,
            ),
            "Confirm and mint",
        )
    }
}
