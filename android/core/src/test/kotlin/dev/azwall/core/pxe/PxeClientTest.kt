package dev.azwall.core.pxe

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Answers with canned JSON and records the calls, like the PXE host would see them. */
private class Recorder(private val replies: Map<String, String>) : PxeCaller {
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun call(method: String, params: JsonObject): JsonElement {
        calls += method to params
        return Json.parseToJsonElement(replies[method] ?: "null")
    }
}

class PxeClientTest {
    private val account = AccountSecrets("main", "0x" + "11".repeat(32), "0x" + "00".repeat(32))

    @Test
    fun openSendsTheNetworkParamsAndKey() = runTest {
        val rec = Recorder(mapOf("open" to """{"nodeVersion":"6.0.0","l1ChainId":11155111,"rollupVersion":3409706519,
            "vkTreeRoot":"0x1","protocolContractsHash":"0x2","blockNumber":7678}"""))
        val check = PxeClient(rec).open("""{"id":"testnet-v6"}""", "0xab")
        assertEquals(3409706519L, check.rollupVersion)
        val (method, params) = rec.calls.single()
        assertEquals("open", method)
        assertEquals("""{"network":{"id":"testnet-v6"},"dbKey":"0xab"}""", params.toString())
    }

    @Test
    fun accountsAndTransactions() = runTest {
        val rec = Recorder(mapOf(
            "addAccount" to """{"address":"0xabc"}""",
            "accountState" to """{"deployed":true}""",
            "deployAccount" to """{"txHash":"0xdead"}""",
            "waitForTx" to """{"txHash":"0xdead","status":"proposed","blockNumber":42}""",
        ))
        val client = PxeClient(rec)
        assertEquals("0xabc", client.addAccount(account))
        assertTrue(client.isDeployed("0xabc"))
        assertEquals("0xdead", client.deployAccount(account))
        assertEquals(Receipt("0xdead", "proposed", 42), client.waitForTx("0xdead"))
        assertEquals("main", rec.calls[0].second["alias"]!!.jsonPrimitive.content)
    }

    @Test
    fun amountsCrossAsDecimalStrings() = runTest {
        val rec = Recorder(mapOf(
            "transfer" to """{"txHash":"0x1"}""",
            "balances" to """[{"token":"0xt","private":"123456789012345678901234","public":"0"}]""",
        ))
        val client = PxeClient(rec)
        val big = BigInteger("123456789012345678901234")
        client.transfer(TransferKind.SHIELD, "0xa", "0xa", "0xt", big)
        assertEquals("shield", rec.calls[0].second["kind"]!!.jsonPrimitive.content)
        assertEquals(big.toString(), rec.calls[0].second["amount"]!!.jsonPrimitive.content)
        val balance = client.balances("0xa", listOf("0xt")).single()
        assertEquals(big, balance.privateAmount)
        assertEquals(1, rec.calls[1].second["tokens"]!!.jsonArray.size)
    }

    @Test
    fun rejectsNonPositiveAmountsAndMissingFields() = runTest {
        val client = PxeClient(Recorder(mapOf("deployAccount" to "{}")))
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { client.transfer(TransferKind.PRIVATE, "a", "b", "t", BigInteger.ZERO) }
        }
        val e = runCatching { client.deployAccount(account) }.exceptionOrNull()
        assertEquals("deployAccount: no txHash in the reply", e?.message)
    }
}
