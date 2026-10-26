package dev.azwall.app.ui

import dev.azwall.core.wallet.ActivityItem
import dev.azwall.core.wallet.Errors
import dev.azwall.core.wallet.ActivityKind
import dev.azwall.core.wallet.ActivityStatus
import dev.azwall.core.wallet.BackgroundTask
import dev.azwall.core.wallet.Phase
import dev.azwall.core.wallet.SessionState
import dev.azwall.core.wallet.Amounts

/** `0x1b17…3d37`. */
fun shortAddress(address: String, keep: Int = 4): String =
    if (address.length <= 2 + keep * 2 + 1) address else "${address.take(2 + keep)}…${address.takeLast(keep)}"

/** A dApp origin as people read it: `https://vote.example` → `vote.example`. */
fun siteOf(origin: String): String = origin.removePrefix("https://")

/** "https://aztec.network/blog?x" → "aztec.network". */
fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

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
    ActivityKind.RECEIVE -> "Received privately"
    ActivityKind.CLAIM_FEE_JUICE -> "Fee Juice claimed"
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
    ActivityKind.RECEIVE -> "Receiving"
    ActivityKind.CLAIM_FEE_JUICE -> "Claiming your Fee Juice"
}

/** "−1.5 TST", "+10 TST" or null for transactions without an amount. */
fun activityAmount(item: ActivityItem): String? {
    // Stored data: a damaged amount shows no amount rather than crashing the list.
    val amount = item.amount?.toBigIntegerOrNull() ?: return null
    val text = "${Amounts.format(amount, item.decimals ?: 0)} ${item.symbol.orEmpty()}".trim()
    return when (item.kind) {
        ActivityKind.SEND_PRIVATE, ActivityKind.SEND_PUBLIC -> "−$text"
        ActivityKind.MINT, ActivityKind.RECEIVE, ActivityKind.CLAIM_FEE_JUICE -> "+$text"
        else -> text
    }
}

/** What Send and Shield say without any token: the same text on both apps. */
const val NO_TOKENS = "Add a token in the wallet first: Send and Shield move the tokens you follow."

/** Human-sized error text: the first line, without the method prefix the bridge adds. */
fun friendlyError(message: String?): String = Errors.friendly(message)

/**
 * The status line's text: what the wallet is doing in the background, or null when idle. A PXE that is far
 * behind explains why balances are not there yet ("Syncing PXE · 1,240 blocks behind (36 h)").
 */
fun statusText(state: SessionState, now: Long = System.currentTimeMillis()): String? {
    if (state.phase == Phase.STARTING) return "Starting the PXE…"
    when (val task = state.task) {
        is BackgroundTask.Syncing -> {
            val from = task.from?.takeIf { it.blocksBehind > 0 } ?: return "Syncing PXE…"
            val blocks = if (from.blocksBehind == 1L) "1 block" else String.format(java.util.Locale.US, "%,d blocks", from.blocksBehind)
            // A PXE that never synced has no block time to measure from.
            val since = if (from.syncedAt > 0) " (${lag(now - from.syncedAt * 1000)})" else ""
            return "Syncing PXE · $blocks behind$since"
        }
        BackgroundTask.FindingTokens -> return "Reading your tokens and history…"
        null -> Unit
    }
    val pending = state.activity.count { it.status == ActivityStatus.PENDING }
    return when (pending) {
        0 -> null
        1 -> "Waiting for 1 transaction to be mined…"
        else -> "Waiting for $pending transactions to be mined…"
    }
}

/** A sync lag, precise enough to explain a wait: "12 min", "36 h", "5 d". */
private fun lag(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 72 * 60 -> "${minutes / 60} h"
        else -> "${minutes / (24 * 60)} d"
    }
}
