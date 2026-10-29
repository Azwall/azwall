package dev.azwall.core.pxe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigInteger

/** Sends one call to the PXE host. [PxeChannel] in the app, a fake in tests. */
fun interface PxeCaller {
    suspend fun call(method: String, params: JsonObject): JsonElement
}

/** Account material as the vault holds it (hex strings). */
@Serializable
data class AccountSecrets(
    val alias: String,
    val signingKey: String,
    val salt: String,
    /** The account's index in the wallet's recovery phrase; null for an account imported from its key. */
    val index: Int? = null,
)

@Serializable
data class NetworkCheck(
    val nodeVersion: String,
    val l1ChainId: Long,
    val rollupVersion: Long,
    val vkTreeRoot: String,
    val protocolContractsHash: String,
    val blockNumber: Long,
    /** "sponsored" (the network's Sponsored FPC pays) or "feeJuice" (the account pays with its own Fee Juice). */
    val fees: String = "sponsored",
)

@Serializable
data class Receipt(val txHash: String, val status: String, val blockNumber: Long? = null, val outcome: TxOutcome = TxOutcome.SUCCESS)

/** The node's statuses folded into what the app shows (pxe/<bundle>/src/wallet.ts `outcomeOf`). */
@Serializable
enum class TxOutcome {
    @SerialName("pending") PENDING,
    @SerialName("success") SUCCESS,
    @SerialName("reverted") REVERTED,
    @SerialName("dropped") DROPPED,
}

@Serializable
data class TokenInfo(val address: String, val name: String, val symbol: String, val decimals: Int)

/**
 * AZTEC deposited for an account on the network's Fee Juice portal, on Ethereum: it becomes Fee Juice once claimed.
 * [ours] when deposited with the secret the wallet derives (it can claim it), [ready] once the message is on Aztec.
 */
@Serializable
data class FeeJuiceDeposit(
    val amount: String,
    val messageHash: String,
    val leafIndex: String,
    val l1Tx: String = "",
    val ours: Boolean,
    val ready: Boolean,
    val claimed: Boolean,
    /** The claim secret, hex, when the wallet knows it ([ours]). */
    val secret: String? = null,
) {
    val units: BigInteger get() = BigInteger(amount)

    /** The wallet can claim it now. */
    val claimable: Boolean get() = ours && ready && !claimed

    /** Deposited, its message not on Aztec yet. */
    val arriving: Boolean get() = ours && !ready && !claimed

    /** Deposited with a secret the wallet does not know: the user can enter it. */
    val locked: Boolean get() = !ours
}

/** Balances in base units, as decimal strings (they exceed 64 bits). */
@Serializable
data class TokenBalance(val token: String, val private: String, val public: String) {
    val privateAmount: BigInteger get() = BigInteger(private)
    val publicAmount: BigInteger get() = BigInteger(public)
}

/** Where the PXE stands: its last synced block (number, time in seconds) and the chain's tip. */
@Serializable
data class SyncStatus(val syncedBlock: Long, val syncedAt: Long, val tip: Long) {
    val blocksBehind: Long get() = (tip - syncedBlock).coerceAtLeast(0)
}

/** One private token transfer of the account, read back from the chain (pxe/<bundle>/src/history.ts). */
@Serializable
data class TokenMove(val token: String, val txHash: String, val block: Long, val at: Long, val from: String, val to: String, val amount: String)

/** A token the account holds that the wallet does not follow yet (pxe/<bundle>/src/discover.ts). */
@Serializable
data class FoundToken(val address: String, val name: String, val symbol: String, val decimals: Int, val private: String, val public: String) {
    val info: TokenInfo get() = TokenInfo(address, name, symbol, decimals)
    val balance: TokenBalance get() = TokenBalance(address, private, public)
}

@Serializable
data class DeployedToken(val token: String, val txHash: String)

/** private: notes to notes · public: public to public · shield: public to own private · unshield: the reverse. */
enum class TransferKind(val wire: String) { PRIVATE("private"), PUBLIC("public"), SHIELD("shield"), UNSHIELD("unshield") }

/** Typed API of a PXE host (pxe/<bundle>/src/api.ts). Every call may take seconds; proving calls, tens. */
class PxeClient(private val caller: PxeCaller) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * This client, with every call carrying [op]: the history row of the transaction it makes. The PXE tells that
     * transaction's hash with it (txSubmitting), so the hash reaches that row whatever else is waiting.
     */
    fun labelled(op: String): PxeClient = PxeClient { method, params -> caller.call(method, JsonObject(params + ("op" to JsonPrimitive(op)))) }

    suspend fun open(networkParamsJson: String, dbKeyHex: String): NetworkCheck = typed(
        "open",
        buildJsonObject { put("network", Json.parseToJsonElement(networkParamsJson)); put("dbKey", dbKeyHex) },
    )

    suspend fun close() {
        caller.call("close", JsonObject(emptyMap()))
    }

    /** Writes the PXE's pending store changes to disk now. */
    suspend fun flush() {
        caller.call("flush", JsonObject(emptyMap()))
    }

    suspend fun newSigningKey(): String = field("newSigningKey", JsonObject(emptyMap()), "signingKey")

    suspend fun addAccount(account: AccountSecrets): String = field("addAccount", secrets(account), "address")

    suspend fun isDeployed(address: String): Boolean =
        caller.call("accountState", params("address" to address)).jsonObject["deployed"]!!.jsonPrimitive.content == "true"

    /** Proves and sends the account deployment; returns the transaction hash. */
    /** Deploys the account; with [claim], the transaction claims that deposit and pays its fee with it. */
    suspend fun deployAccount(account: AccountSecrets, claim: FeeJuiceDeposit? = null): String {
        val p = JsonObject(secrets(account) + listOfNotNull(claim?.let { "claim" to claimJson(it) }))
        return field("deployAccount", p, "txHash")
    }

    /**
     * Fee Juice bridged to [owner] from Ethereum, claimed or not (none where the network has no Ethereum side). The PXE
     * knows the public secrets; [secrets] are the ones the user entered for other deposits.
     */
    suspend fun feeJuiceDeposits(owner: String, secrets: List<String> = emptyList()): List<FeeJuiceDeposit> {
        val p = buildJsonObject { put("owner", owner); putJsonArray("secrets") { secrets.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
        return json.decodeFromJsonElement(caller.call("feeJuiceDeposits", p).jsonObject["deposits"]!!)
    }

    /** Claims [deposit] for an active [owner]; the transaction pays its fee with the Fee Juice it claims. */
    suspend fun claimFeeJuice(owner: String, deposit: FeeJuiceDeposit): String =
        field("claimFeeJuice", buildJsonObject { put("owner", owner); put("claim", claimJson(deposit)) }, "txHash")

    private fun claimJson(d: FeeJuiceDeposit) = buildJsonObject {
        put("amount", d.amount); put("leafIndex", d.leafIndex); put("secret", d.secret ?: throw PxeException("this deposit's claim secret is unknown"))
    }

    suspend fun waitForTx(txHash: String): Receipt = typed("waitForTx", params("txHash" to txHash))

    /** The receipt as it stands, without waiting. */
    suspend fun txReceipt(txHash: String): Receipt = typed("txReceipt", params("txHash" to txHash))

    suspend fun blockNumber(): Long = caller.call("blockNumber", JsonObject(emptyMap())).jsonPrimitive.long

    suspend fun tokenInfo(token: String, from: String): TokenInfo = typed("tokenInfo", params("token" to token, "from" to from))

    suspend fun balances(owner: String, tokens: List<String>): List<TokenBalance> = typed(
        "balances",
        buildJsonObject { put("owner", owner); putJsonArray("tokens") { tokens.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } },
    )

    /** The private transfers of [owner] on [tokens], as the PXE decrypts them from the chain. */
    suspend fun tokenHistory(owner: String, tokens: List<String>): List<TokenMove> = typed(
        "tokenHistory",
        buildJsonObject { put("owner", owner); putJsonArray("tokens") { tokens.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } },
    )

    suspend fun syncStatus(): SyncStatus = typed("syncStatus", JsonObject(emptyMap()))

    /** Base units (18 decimals) of the Fee Juice `owner` holds. */
    suspend fun feeJuiceBalance(owner: String): String =
        caller.call("feeJuiceBalance", buildJsonObject { put("owner", owner) }).jsonObject["balance"]!!.jsonPrimitive.content

    /** Registers followed tokens in the PXE again (no-op for the ones it knows); failures are skipped. */
    suspend fun registerTokens(tokens: List<String>) {
        caller.call("registerTokens", buildJsonObject { putJsonArray("tokens") { tokens.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } })
    }

    /** Tokens registered in the PXE (by dApps too) outside [known], that [owner] holds. */
    suspend fun discoverTokens(owner: String, known: List<String>): List<FoundToken> = typed(
        "discoverTokens",
        buildJsonObject { put("owner", owner); putJsonArray("known") { known.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } },
    )

    suspend fun transfer(kind: TransferKind, from: String, to: String, token: String, amount: BigInteger): String {
        require(amount.signum() > 0) { "amount must be positive" }
        val p = params("kind" to kind.wire, "from" to from, "to" to to, "token" to token, "amount" to amount.toString())
        return field("transfer", p, "txHash")
    }

    suspend fun deployToken(admin: String, name: String, symbol: String, decimals: Int): DeployedToken = typed(
        "deployToken",
        buildJsonObject { put("admin", admin); put("name", name); put("symbol", symbol); put("decimals", decimals) },
    )

    suspend fun mint(admin: String, token: String, to: String, amount: BigInteger, toPrivate: Boolean): String {
        val p = buildJsonObject {
            put("admin", admin); put("token", token); put("to", to); put("amount", amount.toString()); put("private", toPrivate)
        }
        return field("mint", p, "txHash")
    }

    /** An untyped call, for methods whose payload is opaque to the app (dApp messages). */
    suspend fun callRaw(method: String, params: JsonObject): JsonElement = caller.call(method, params)

    private suspend inline fun <reified T> typed(method: String, params: JsonObject): T =
        json.decodeFromJsonElement(caller.call(method, params))

    private suspend fun field(method: String, params: JsonObject, key: String): String =
        caller.call(method, params).jsonObject[key]?.jsonPrimitive?.content ?: throw PxeException("$method: no $key in the reply")

    private fun secrets(account: AccountSecrets): JsonObject = json.encodeToJsonElement(account).jsonObject

    private fun params(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
}
