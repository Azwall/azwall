package dev.azwall.app.ui

import dev.azwall.core.wallet.ActivityItem
import dev.azwall.core.wallet.ActivityKind
import dev.azwall.core.wallet.Amounts
import java.math.BigInteger

/** `0x1b17…3d37`. */
fun shortAddress(address: String, keep: Int = 4): String =
    if (address.length <= 2 + keep * 2 + 1) address else "${address.take(2 + keep)}…${address.takeLast(keep)}"

/** "now", "5 min", "3 h", "2 d". */
fun timeAgo(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - epochMillis).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> "${minutes / 60} h"
        else -> "${minutes / (24 * 60)} d"
    }
}

fun activityTitle(item: ActivityItem): String = when (item.kind) {
    ActivityKind.DEPLOY_ACCOUNT -> "Account activated"
    ActivityKind.SEND_PRIVATE -> "Sent privately"
    ActivityKind.SEND_PUBLIC -> "Sent publicly"
    ActivityKind.SHIELD -> "Made private"
    ActivityKind.UNSHIELD -> "Made public"
    ActivityKind.MINT -> "Minted"
    ActivityKind.DEPLOY_TOKEN -> "Token created" + (item.symbol?.let { " ($it)" } ?: "")
    ActivityKind.DAPP -> item.summary ?: "App transaction"
}

/** What the proving screen says while [kind] is being proven. */
fun provingTitle(kind: ActivityKind): String = when (kind) {
    ActivityKind.DEPLOY_ACCOUNT -> "Activating your account"
    ActivityKind.SEND_PRIVATE -> "Sending privately"
    ActivityKind.SEND_PUBLIC -> "Sending publicly"
    ActivityKind.SHIELD -> "Making funds private"
    ActivityKind.UNSHIELD -> "Making funds public"
    ActivityKind.MINT -> "Minting"
    ActivityKind.DEPLOY_TOKEN -> "Creating the token"
    ActivityKind.DAPP -> "Proving the app's transaction"
}

/** "−1.5 TST", "+10 TST" or null for transactions without an amount. */
fun activityAmount(item: ActivityItem): String? {
    val amount = item.amount?.let(::BigInteger) ?: return null
    val text = "${Amounts.format(amount, item.decimals ?: 0)} ${item.symbol.orEmpty()}".trim()
    return when (item.kind) {
        ActivityKind.SEND_PRIVATE, ActivityKind.SEND_PUBLIC -> "−$text"
        ActivityKind.MINT -> "+$text"
        else -> text
    }
}

/** Human-sized error text: the first line, without the method prefix the bridge adds. */
fun friendlyError(message: String?): String =
    message.orEmpty().lineSequence().firstOrNull().orEmpty().substringAfter(": ", message.orEmpty()).ifBlank { "Something went wrong" }

/** A 32-byte hex key as four lines of four 4-character groups, easy to copy by hand. */
fun groupKey(key: String): String =
    key.removePrefix("0x").chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }
