package dev.azwall.core.pxe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
data class AccountSecrets(val alias: String, val signingKey: String, val salt: String)

@Serializable
data class NetworkCheck(
    val nodeVersion: String,
    val l1ChainId: Long,
    val rollupVersion: Long,
    val vkTreeRoot: String,
    val protocolContractsHash: String,
    val blockNumber: Long,
)

@Serializable
data class Receipt(val txHash: String, val status: String, val blockNumber: Long? = null)

@Serializable
data class TokenInfo(val address: String, val name: String, val symbol: String, val decimals: Int)

/** Balances in base units, as decimal strings (they exceed 64 bits). */
@Serializable
data class TokenBalance(val token: String, val private: String, val public: String) {
    val privateAmount: BigInteger get() = BigInteger(private)
    val publicAmount: BigInteger get() = BigInteger(public)
}

@Serializable
data class DeployedToken(val token: String, val txHash: String)

/** private: notes to notes · public: public to public · shield: public to own private · unshield: the reverse. */
enum class TransferKind(val wire: String) { PRIVATE("private"), PUBLIC("public"), SHIELD("shield"), UNSHIELD("unshield") }

/** Typed API of a PXE host (pxe/<bundle>/src/api.ts). Every call may take seconds; proving calls, tens. */
class PxeClient(private val caller: PxeCaller) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun open(networkParamsJson: String, dbKeyHex: String): NetworkCheck = typed(
        "open",
        buildJsonObject { put("network", Json.parseToJsonElement(networkParamsJson)); put("dbKey", dbKeyHex) },
    )

    suspend fun close() {
        caller.call("close", JsonObject(emptyMap()))
    }

    suspend fun newSigningKey(): String = field("newSigningKey", JsonObject(emptyMap()), "signingKey")

    suspend fun addAccount(account: AccountSecrets): String = field("addAccount", secrets(account), "address")

    suspend fun isDeployed(address: String): Boolean =
        caller.call("accountState", params("address" to address)).jsonObject["deployed"]!!.jsonPrimitive.content == "true"

    /** Proves and sends the account deployment; returns the transaction hash. */
    suspend fun deployAccount(account: AccountSecrets): String = field("deployAccount", secrets(account), "txHash")

    suspend fun waitForTx(txHash: String): Receipt = typed("waitForTx", params("txHash" to txHash))

    suspend fun blockNumber(): Long = caller.call("blockNumber", JsonObject(emptyMap())).jsonPrimitive.long

    suspend fun tokenInfo(token: String, from: String): TokenInfo = typed("tokenInfo", params("token" to token, "from" to from))

    suspend fun balances(owner: String, tokens: List<String>): List<TokenBalance> = typed(
        "balances",
        buildJsonObject { put("owner", owner); putJsonArray("tokens") { tokens.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } },
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

    private suspend inline fun <reified T> typed(method: String, params: JsonObject): T =
        json.decodeFromJsonElement(caller.call(method, params))

    private suspend fun field(method: String, params: JsonObject, key: String): String =
        caller.call(method, params).jsonObject[key]?.jsonPrimitive?.content ?: throw PxeException("$method: no $key in the reply")

    private fun secrets(account: AccountSecrets): JsonObject = json.encodeToJsonElement(account).jsonObject

    private fun params(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
}
