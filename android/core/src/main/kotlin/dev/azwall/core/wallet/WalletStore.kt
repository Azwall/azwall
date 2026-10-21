package dev.azwall.core.wallet

import dev.azwall.core.pxe.TokenInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class ActivityKind { DEPLOY_ACCOUNT, SEND_PRIVATE, SEND_PUBLIC, SHIELD, UNSHIELD, MINT, DEPLOY_TOKEN, DAPP, RECEIVE }

enum class ActivityStatus { PROVING, PENDING, CONFIRMED, FAILED }

/** One transaction made by this wallet. The PXE does not keep a history, so the app does. */
@Serializable
data class ActivityItem(
    val id: String,
    val account: String,
    val kind: ActivityKind,
    val status: ActivityStatus,
    val createdAt: Long,
    val txHash: String? = null,
    val token: String? = null,
    val symbol: String? = null,
    val decimals: Int? = null,
    val amount: String? = null,
    val counterparty: String? = null,
    val blockNumber: Long? = null,
    val error: String? = null,
    /** DAPP items: the site that asked for the transaction, and what it does (its first call). */
    val origin: String? = null,
    val summary: String? = null,
)

@Serializable
private data class StoredNetwork(
    val tokens: List<TokenInfo> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    /** Found tokens the user chose not to follow: not suggested again. */
    val dismissed: List<String> = emptyList(),
    /** The token each account shows at the top of the home screen (account → token address). */
    val primary: Map<String, String> = emptyMap(),
    /** Known tokens already followed once: removing one keeps it removed. */
    val seeded: List<String> = emptyList(),
)

/**
 * Non-secret wallet data of one network: the tokens the user follows and the transaction history.
 * One JSON file per network (`wallet-<network>.json`), written atomically.
 */
class WalletStore(private val dir: File, private val networkId: String) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file get() = File(dir, "wallet-$networkId.json")
    private var data: StoredNetwork = load()

    val tokens: List<TokenInfo> get() = data.tokens

    fun activity(account: String): List<ActivityItem> = data.activity.filter { it.account == account }.sortedByDescending { it.createdAt }

    fun addToken(token: TokenInfo) {
        if (data.tokens.any { it.address.equals(token.address, ignoreCase = true) }) return
        save(data.copy(tokens = data.tokens + token))
    }

    val dismissed: List<String> get() = data.dismissed

    /** Follows the network's known tokens not followed before (once each: a removed one stays removed). */
    fun seed(known: List<TokenInfo>) {
        val fresh = known.filter { k -> data.seeded.none { it.equals(k.address, ignoreCase = true) } }
        if (fresh.isEmpty()) return
        val tokens = data.tokens + fresh.filter { f -> data.tokens.none { it.address.equals(f.address, ignoreCase = true) } }
        save(data.copy(tokens = tokens, seeded = data.seeded + fresh.map { it.address }))
    }

    fun primary(account: String): String? = data.primary[account]

    fun setPrimary(account: String, token: String) = save(data.copy(primary = data.primary + (account to token)))

    fun dismiss(address: String) {
        if (address !in data.dismissed) save(data.copy(dismissed = data.dismissed + address))
    }

    fun removeToken(address: String) = save(data.copy(tokens = data.tokens.filterNot { it.address.equals(address, ignoreCase = true) }))

    /** Adds items the store does not have yet (by id), in one write. */
    fun addMissing(items: List<ActivityItem>) {
        val ids = data.activity.mapTo(HashSet()) { it.id }
        val fresh = items.filter { it.id !in ids }
        if (fresh.isEmpty()) return
        save(data.copy(activity = (data.activity + fresh).sortedByDescending { it.createdAt }.take(MAX_ACTIVITY)))
    }

    /** Inserts or replaces the item with the same id; keeps the newest [MAX_ACTIVITY] items. */
    fun upsert(item: ActivityItem) {
        val others = data.activity.filterNot { it.id == item.id }
        save(data.copy(activity = (others + item).sortedByDescending { it.createdAt }.take(MAX_ACTIVITY)))
    }

    /**
     * Sent transactions without a settled outcome: still PENDING, or FAILED without a receipt saying so (older
     * versions marked an interrupted wait as a failure).
     */
    fun unsettled(): List<ActivityItem> = data.activity.filter {
        it.txHash != null && (it.status == ActivityStatus.PENDING ||
            it.status == ActivityStatus.FAILED && it.error != Transactions.REVERTED && it.error != Transactions.DROPPED)
    }

    /** Items left PROVING by a crash cannot finish: their proof died with the process. */
    fun failInterrupted() {
        val stale = data.activity.filter { it.status == ActivityStatus.PROVING }
        stale.forEach { upsert(it.copy(status = ActivityStatus.FAILED, error = "interrupted before the proof was sent")) }
    }

    private fun load(): StoredNetwork =
        runCatching { json.decodeFromString(StoredNetwork.serializer(), file.readText()) }.getOrDefault(StoredNetwork())

    private fun save(next: StoredNetwork) {
        data = next
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(StoredNetwork.serializer(), next))
        tmp.renameTo(file)
    }

    companion object {
        const val MAX_ACTIVITY = 200
    }
}
