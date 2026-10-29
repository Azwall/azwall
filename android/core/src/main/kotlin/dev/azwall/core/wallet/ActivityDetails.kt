package dev.azwall.core.wallet

import java.math.BigInteger

/**
 * Everything the wallet knows about one transaction of the history, line by line, for its detail sheet: the exact
 * amount, addresses and hash in full (to check them or look them up elsewhere). `date` comes formatted by the app.
 */
object ActivityDetails {
    fun of(item: ActivityItem, date: String): List<Pair<String, String>> = buildList {
        add("Status" to status(item))
        if (item.status == ActivityStatus.FAILED || item.uncertain) add("Error" to Errors.friendly(item.error))
        exactAmount(item)?.let { add("Amount" to it) }
        item.token?.let { add("Token" to it) }
        item.counterparty?.let { add((if (item.kind == ActivityKind.RECEIVE) "From" else "To") to it) }
        add("Account" to item.account)
        item.origin?.let { add("Site" to it) }
        item.summary?.let { add("Action" to it) }
        item.txHash?.let { add("Transaction hash" to it) }
        add("Date" to date)
    }

    private fun status(item: ActivityItem): String = if (item.uncertain) "Unknown: it may have been sent" else when (item.status) {
        ActivityStatus.PROVING -> "Being proven on this phone"
        ActivityStatus.PENDING -> "Sent, waiting for a block"
        ActivityStatus.CONFIRMED -> item.blockNumber?.let { "Confirmed in block $it" } ?: "Confirmed"
        ActivityStatus.FAILED -> "Failed"
    }

    /** Every decimal, unlike the list's rounded amount. */
    private fun exactAmount(item: ActivityItem): String? {
        val units = item.amount?.toBigIntegerOrNull() ?: return null
        val decimals = item.decimals ?: return "$units ${item.symbol.orEmpty()}".trim()
        return "${Amounts.format(units, decimals, decimals)} ${item.symbol.orEmpty()}".trim()
    }

    // Null is the answer for an amount that is not a number: the raw text is shown instead.
    private fun String.toBigIntegerOrNull(): BigInteger? = runCatching { BigInteger(this) }.getOrNull()
}
