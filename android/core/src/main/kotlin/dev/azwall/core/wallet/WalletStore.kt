package dev.azwall.core.wallet

import dev.azwall.core.pxe.TokenInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class ActivityKind { DEPLOY_ACCOUNT, SEND_PRIVATE, SEND_PUBLIC, SHIELD, UNSHIELD, MINT, DEPLOY_TOKEN }

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
)

@Serializable
private data class StoredNetwork(val tokens: List<TokenInfo> = emptyList(), val activity: List<ActivityItem> = emptyList())

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

    fun removeToken(address: String) = save(data.copy(tokens = data.tokens.filterNot { it.address.equals(address, ignoreCase = true) }))

    /** Inserts or replaces the item with the same id; keeps the newest [MAX_ACTIVITY] items. */
    fun upsert(item: ActivityItem) {
        val others = data.activity.filterNot { it.id == item.id }
        save(data.copy(activity = (others + item).sortedByDescending { it.createdAt }.take(MAX_ACTIVITY)))
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
