package dev.azwall.core.wallet

import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.TestPhrases
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.PxeCaller
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.pxe.PxeException
import dev.azwall.core.pxe.TokenBalance
import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.pxe.TransferKind
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A PXE host in memory: addresses derive from keys, balances and deployment are settable. */
internal class FakePxe : PxeCaller {
    var deployed = false
    /** What the network declares: "sponsored", or "feeJuice" (the account pays). */
    var fees = "sponsored"
    /** The rollup version the node reports: a new one means the network was redeployed. */
    var rollup = 2
    /** Balance reads fail, as with an unreachable node. */
    var failBalances = false
    /** The user's claim secrets each feeJuiceDeposits call carried. */
    val depositQueries = mutableListOf<List<String>>()
    /** A secret and the deposits read once it is among the secrets tried, as a deposit made with it reads. */
    var unlockedBy: Pair<String, String>? = null
    /** What feeJuiceDeposits answers: Fee Juice bridged to the account (JSON list). */
    var deposits = "[]"
    /** Base units of Fee Juice every account holds. */
    var feeJuice = "1500000000000000000"
    var failOpen: String? = null
    val mined = CompletableDeferred<Unit>()
    var waitError: String? = null
    var receiptOutcome = "success"
    val discoveryKnown = mutableListOf<List<String>>()
    val sent = mutableListOf<JsonObject>()
    val minted = mutableListOf<JsonObject>()
    val calls = mutableListOf<String>()
    /** Run inside "transfer", like the user tapping Cancel while it is proven; the app's prove handler then checks. */
    var whileProving: suspend () -> Unit = {}
    /** Run once the proof is released, as the PXE sends the transaction: too late to cancel. */
    var whileSending: suspend () -> Unit = {}
    /** Holds the first account's balances until completed: a refresh still running when the user switches. */
    var hold: CompletableDeferred<Unit>? = null

    override suspend fun call(method: String, params: JsonObject): JsonElement {
        fun p(k: String) = params[k]!!.jsonPrimitive.content
        calls += method
        val reply = when (method) {
            "open" -> failOpen?.let { throw PxeException("open: $it") }
                ?: """{"nodeVersion":"6.0.0","l1ChainId":1,"rollupVersion":$rollup,"vkTreeRoot":"0x","protocolContractsHash":"0x","blockNumber":10,"fees":"$fees"}"""
            "feeJuiceBalance" -> """{"balance":"$feeJuice"}"""
            "addAccount" -> """{"address":"addr-${p("signingKey").takeLast(4)}"}"""
            "newSigningKey" -> """{"signingKey":"0x${"cd".repeat(32)}"}"""
            "accountState" -> """{"deployed":$deployed}"""
            "balances" -> {
                if (failBalances) error("node unreachable")
                val first = p("owner") == "addr-abab"
                if (first) hold?.await()
                """[{"token":"0xt","private":"${if (first) "2500000" else "7000000"}","public":"0"}]"""
            }
            "tokenInfo" -> """{"address":"${p("token")}","name":"Test","symbol":"TST","decimals":6}"""
            "mint" -> { minted += params; """{"txHash":"0xmint"}""" }
            "feeJuiceDeposits" -> {
                val secrets = params["secrets"]!!.jsonArray.map { it.jsonPrimitive.content }
                depositQueries += secrets
                val unlocked = unlockedBy?.takeIf { it.first in secrets }?.second
                """{"deposits":${unlocked ?: deposits}}"""
            }
            "deployAccount", "transfer", "claimFeeJuice" -> {
                whileProving()
                ProofGate.release()
                whileSending()
                sent += params
                """{"txHash":"0xhash${sent.size}"}"""
            }
            "waitForTx" -> { waitError?.let { throw PxeException(it) }; mined.await(); """{"txHash":"${p("txHash")}","status":"proposed","blockNumber":11}""" }
            "discoverTokens" -> {
                val known = params["known"]!!.jsonArray.map { it.jsonPrimitive.content }.also { discoveryKnown += it }
                if ("0xpal" in known) "[]" else """[{"address":"0xpal","name":"Palmyr USD","symbol":"PUSD","decimals":6,"private":"0","public":"5000000"}]"""
            }
            "txReceipt" -> """{"txHash":"${p("txHash")}","status":"proposed","blockNumber":12,"outcome":"$receiptOutcome"}"""
            else -> "null"
        }
        return Json.parseToJsonElement(reply)
    }
}

internal object FakeBackend : WalletBackend {
    override fun networks() = emptyList<NetworkInfo>()
    override fun defaultNetworkId() = "testnet-v6"
    override fun checkNode(networkId: String) = NodeCheck("x", 0, true, emptyList())
    override fun pxeOpenParams(networkId: String) = """{"id":"$networkId"}"""
    override fun prepareProver(networkId: String) {}
    override fun prove(networkId: String, request: ByteArray) = request
    override fun newRecoveryPhrase() = TestPhrases.PHRASE
    override fun normalizeRecoveryPhrase(phrase: String) = TestPhrases.normalize(phrase)
    override fun accountSigningKey(phrase: String, index: Int) = TestPhrases.key(index)
}

internal object PlainBox : SecretBox {
    override fun seal(plain: ByteArray) = plain
    override fun open(sealed: ByteArray) = sealed
}

/** [vault] unlocked with [contents], as the app holds it (VaultState). */
internal fun unlocked(vault: dev.azwall.core.vault.Vault, contents: dev.azwall.core.vault.VaultContents) =
    dev.azwall.core.vault.VaultState(vault).also { state -> kotlinx.coroutines.runBlocking { state.open { contents } } }
