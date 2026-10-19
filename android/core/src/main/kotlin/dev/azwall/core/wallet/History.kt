package dev.azwall.core.wallet

import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.pxe.TokenMove

/**
 * Turns the private transfers the PXE reads back from the chain into activity items, for the ones the history
 * does not have: transfers made before the wallet recorded dApp transactions, or from another wallet with the
 * same keys. A transaction the wallet already recorded (same hash) keeps its own, richer item.
 */
internal object History {
    private val ZERO = Regex("^0x0*$")

    fun items(owner: String, moves: List<TokenMove>, tokens: List<TokenInfo>, known: List<ActivityItem>): List<ActivityItem> {
        val recorded = known.mapNotNullTo(HashSet()) { it.txHash?.lowercase() }
        return moves.filter { it.txHash.lowercase() !in recorded }.mapNotNull { move ->
            val token = tokens.firstOrNull { it.address.equals(move.token, ignoreCase = true) } ?: return@mapNotNull null
            item(owner, move, token)
        }
    }

    private fun item(owner: String, move: TokenMove, token: TokenInfo): ActivityItem? {
        val fromMe = move.from.equals(owner, ignoreCase = true)
        val toMe = move.to.equals(owner, ignoreCase = true)
        val kind = when {
            fromMe && toMe -> return null // change returned to the sender: not a movement
            fromMe -> ActivityKind.SEND_PRIVATE
            ZERO.matches(move.from) -> ActivityKind.MINT
            else -> ActivityKind.RECEIVE
        }
        return ActivityItem(
            id = "chain-${move.txHash}-${move.token}-${move.to}-${move.amount}",
            account = owner,
            kind = kind,
            status = ActivityStatus.CONFIRMED,
            createdAt = move.at,
            txHash = move.txHash,
            token = token.address,
            symbol = token.symbol,
            decimals = token.decimals,
            amount = move.amount,
            counterparty = (if (fromMe) move.to else move.from).takeUnless { ZERO.matches(it) },
            blockNumber = move.block,
        )
    }
}
