package dev.azwall.core.wallet

import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.PxeCaller
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.pxe.PxeException
import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.pxe.TransferKind
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigInteger

/** A PXE host in memory: addresses derive from keys, balances and deployment are settable. */
private class FakePxe : PxeCaller {
    var deployed = false
    var failOpen: String? = null
    val mined = CompletableDeferred<Unit>()
    var waitError: String? = null
    var receiptOutcome = "success"
    val discoveryKnown = mutableListOf<List<String>>()
    val sent = mutableListOf<JsonObject>()

    override suspend fun call(method: String, params: JsonObject): JsonElement {
        fun p(k: String) = params[k]!!.jsonPrimitive.content
        val reply = when (method) {
            "open" -> failOpen?.let { throw PxeException("open: $it") }
                ?: """{"nodeVersion":"6.0.0-rc.3","l1ChainId":1,"rollupVersion":2,"vkTreeRoot":"0x","protocolContractsHash":"0x","blockNumber":10}"""
            "addAccount" -> """{"address":"addr-${p("signingKey").takeLast(4)}"}"""
            "newSigningKey" -> """{"signingKey":"0x${"cd".repeat(32)}"}"""
            "accountState" -> """{"deployed":$deployed}"""
            "balances" -> """[{"token":"0xt","private":"2500000","public":"0"}]"""
            "tokenInfo" -> """{"address":"${p("token")}","name":"Test","symbol":"TST","decimals":6}"""
            "deployAccount", "transfer" -> { sent += params; """{"txHash":"0xhash${sent.size}"}""" }
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

private object FakeBackend : WalletBackend {
    override fun networks() = emptyList<NetworkInfo>()
    override fun defaultNetworkId() = "testnet-v6"
    override fun checkNode(networkId: String) = NodeCheck("x", 0, true, emptyList())
    override fun pxeOpenParams(networkId: String) = """{"id":"$networkId"}"""
    override fun prepareProver(networkId: String) {}
    override fun prove(networkId: String, request: ByteArray) = request
}

private object PlainBox : SecretBox {
    override fun seal(plain: ByteArray) = plain
    override fun open(sealed: ByteArray) = sealed
}

class WalletSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val network = NetworkInfo("testnet-v6", "Testnet", "6.0.0-rc.3", "6.0.0-rc.6", "node", "v6", true)
    private val pxe = FakePxe()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelSessions() = scopes.forEach { it.cancel() }

    private fun TestScope.session(): WalletSession {
        val vault = Vault(tmp.root.resolve("vault"), PlainBox)
        val contents = vault.create(dev.azwall.core.pxe.AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT))
        val launcher = PxeLauncher { object : RunningPxe { override val client = PxeClient(pxe); override val events = emptyFlow<PxeEvent>(); override fun stop() {} } }
        // The session runs endless collectors: a scope on the test scheduler (advanceUntilIdle runs it), cancelled after.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        return WalletSession(FakeBackend, launcher, vault, contents, tmp.root, network, scope)
    }

    @Test
    fun openRegistersAccountsAndSelectsTheFirst() = runTest {
        val s = session().apply { open() }
        val state = s.state.value
        assertEquals(Phase.READY, state.phase)
        assertEquals(10L, state.check?.blockNumber)
        assertEquals("addr-abab", state.selected)
        assertEquals(false, state.account?.deployed)
    }

    @Test
    fun openFailuresAreReportedInTheState() = runTest {
        pxe.failOpen = "network testnet-v6 is not compatible"
        val state = session().apply { open() }.state.value
        assertEquals(Phase.FAILED, state.phase)
        assertEquals("open: network testnet-v6 is not compatible", state.error)
    }

    @Test
    fun tokensAndBalancesAreFollowedOnceDeployed() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        s.addToken("0xt")
        val token = s.state.value.tokens.single()
        assertEquals("TST", token.info.symbol)
        assertEquals(BigInteger("2500000"), token.balance?.privateAmount)
    }

    @Test
    fun transactionsGoFromProvingToConfirmed() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        val token = TokenInfo("0xt", "Test", "TST", 6)
        val sent = s.transactions.send(TransferKind.PRIVATE, token, " addr-x ", BigInteger("1000000"))
        assertEquals(ActivityStatus.PENDING, sent.status)
        assertEquals("addr-x", pxe.sent.single()["to"]!!.jsonPrimitive.content)
        assertNull(s.transactions.proving.value)
        pxe.mined.complete(Unit)
        advanceUntilIdle()
        val item = s.state.value.activity.single()
        assertEquals(ActivityStatus.CONFIRMED, item.status)
        assertEquals(11L, item.blockNumber)
    }

    @Test
    fun shieldingSendsToTheSenderAndAccountsCanBeAdded() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        s.transactions.send(TransferKind.SHIELD, TokenInfo("0xt", "Test", "TST", 6), "", BigInteger.TEN)
        assertEquals("addr-abab", pxe.sent.single()["to"]!!.jsonPrimitive.content)
        val added = s.addAccount("Savings", importedKey = "0x" + "12".repeat(32))
        assertEquals("addr-1212", added.address)
        assertEquals(listOf("main", "Savings"), s.state.value.accounts.map { it.alias })
        assertTrue(s.state.value.selected == added.address)
        pxe.mined.complete(Unit) // let the background confirmation finish
    }

    @Test
    fun anInterruptedWaitAsksTheNodeInsteadOfFailing() = runTest {
        pxe.deployed = true
        pxe.waitError = "waitForTx: the PXE host was stopped"
        val s = session().apply { open() }
        s.transactions.send(TransferKind.UNSHIELD, TokenInfo("0xt", "Test", "TST", 6), "", BigInteger("1"))
        advanceUntilIdle()
        val item = s.state.value.activity.single()
        assertEquals(ActivityStatus.CONFIRMED, item.status)
        assertEquals(12L, item.blockNumber)
    }

    @Test
    fun transactionsMarkedFailedWithoutAReceiptAreRepairedOnOpen() = runTest {
        WalletStore(tmp.root, network.id).upsert(
            ActivityItem("old", "addr-abab", ActivityKind.UNSHIELD, ActivityStatus.FAILED, 1, txHash = "0xold", error = "waitForTx: the PXE host was stopped"),
        )
        pxe.deployed = true
        pxe.mined.complete(Unit)
        val s = session().apply { open() }
        advanceUntilIdle()
        assertEquals(ActivityStatus.CONFIRMED, s.state.value.activity.single().status)
    }

    @Test
    fun revertedTransactionsStayFailed() = runTest {
        pxe.deployed = true
        pxe.waitError = "reverted"
        pxe.receiptOutcome = "reverted"
        val s = session().apply { open() }
        s.transactions.send(TransferKind.PRIVATE, TokenInfo("0xt", "Test", "TST", 6), "addr-x", BigInteger("1"))
        advanceUntilIdle()
        val item = s.state.value.activity.single()
        assertEquals(ActivityStatus.FAILED, item.status)
        assertEquals(Transactions.REVERTED, item.error)
        assertTrue(WalletStore(tmp.root, network.id).unsettled().isEmpty())
    }

    @Test
    fun tokensFromDappsAreSuggestedAndFollowedInOneTap() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        val found = s.state.value.found.single()
        assertEquals("PUSD", found.symbol)
        s.followFound(found)
        assertTrue(s.state.value.found.isEmpty())
        assertEquals(BigInteger("5000000"), s.state.value.tokens.single().balance?.publicAmount)
        assertEquals(listOf("0xpal"), WalletStore(tmp.root, network.id).tokens.map { it.address })
    }

    @Test
    fun dismissedTokensAreNotSuggestedAgain() = runTest {
        pxe.deployed = true
        val s = session().apply { open() }
        s.dismissFound(s.state.value.found.single())
        s.select("addr-abab") // resets the discovery throttle
        advanceUntilIdle()
        assertTrue(s.state.value.found.isEmpty())
        assertTrue("0xpal" in pxe.discoveryKnown.last())
    }
}
