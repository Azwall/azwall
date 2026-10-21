package dev.azwall.core.wallet

import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.AccountSecrets
import dev.azwall.core.pxe.PxeCaller
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DappApprovalsTest {
    @Test
    fun approveWaitsForTheUserDecision() = runTest {
        val approvals = DappApprovals()
        val params = Json.parseToJsonElement(
            """{"kind":"capabilities","tab":7,"origin":"https://vote.example","title":"Vote asks for access","details":["accounts"],"emojis":"🦊🌵"}""",
        ).jsonObject
        val reply = async { approvals.approve(params) }
        advanceUntilIdle()
        val request = approvals.pending.value.single()
        assertEquals(ApprovalKind.CAPABILITIES, request.kind)
        assertEquals(7, request.tab)
        assertEquals("🦊🌵", request.emojis)
        approvals.decide(request.id, true)
        assertTrue(reply.await().jsonObject["approved"]!!.jsonPrimitive.boolean)
        assertTrue(approvals.pending.value.isEmpty())
    }

    @Test
    fun rejectAllAnswersNo() = runTest {
        val approvals = DappApprovals()
        val answer = async { approvals.ask(ApprovalKind.TRANSACTION, 1, "https://a", "Send?", emptyList(), null) }
        advanceUntilIdle()
        approvals.rejectAll()
        assertFalse(answer.await())
    }

    @Test
    fun aClosedPageLosesOnlyItsOwnRequests() = runTest {
        val approvals = DappApprovals()
        val left = async { approvals.ask(ApprovalKind.CONNECT, 1, "https://legit.app", "Connect?", emptyList(), null) }
        val other = async { approvals.ask(ApprovalKind.CONNECT, 2, "https://other.app", "Connect?", emptyList(), null) }
        advanceUntilIdle()
        approvals.rejectTab(1)
        assertFalse(left.await())
        assertEquals(2, approvals.pending.value.single().tab)
        approvals.decide(approvals.pending.value.single().id, true)
        assertTrue(other.await())
    }
}

private class GatewayPxe : PxeCaller {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val events = MutableSharedFlow<PxeEvent>(extraBufferCapacity = 16)

    override suspend fun call(method: String, params: JsonObject): JsonElement {
        calls += method to params
        val reply = when (method) {
            "open" -> """{"nodeVersion":"6","l1ChainId":1,"rollupVersion":2,"vkTreeRoot":"0x","protocolContractsHash":"0x","blockNumber":1}"""
            "addAccount" -> """{"address":"0xme"}"""
            "accountState" -> """{"deployed":true}"""
            "waitForTx" -> """{"txHash":"0xhash","status":"success","blockNumber":42}"""
            else -> "null"
        }
        return Json.parseToJsonElement(reply)
    }
}

class DappLinkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pxe = GatewayPxe()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelSessions() = scopes.forEach { it.cancel() }

    private object Backend : WalletBackend {
        override fun networks() = emptyList<NetworkInfo>()
        override fun defaultNetworkId() = "n"
        override fun checkNode(networkId: String) = NodeCheck("6", 0, true, emptyList())
        override fun pxeOpenParams(networkId: String) = "{}"
        override fun prepareProver(networkId: String) {}
        override fun prove(networkId: String, request: ByteArray) = request
    }

    private suspend fun TestScope.session(): WalletSession {
        val box = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        }
        val vault = Vault(tmp.root.resolve("v"), box)
        val contents = vault.create(AccountSecrets("main", "0x" + "12".repeat(32), Vault.ZERO_SALT))
        val launcher = PxeLauncher {
            object : RunningPxe {
                override val client = PxeClient(pxe)
                override val events = pxe.events
                override fun stop() {}
            }
        }
        val network = NetworkInfo("n", "N", "6", "6", "h", "v6", true)
        // A scope on the test scheduler (advanceUntilIdle runs it), cancelled after each test.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        return WalletSession(Backend, launcher, vault, contents, tmp.root, network, scope).apply { open() }
    }

    @Test
    fun pageMessagesCarryTheOriginAndTheSelectedAccount() = runTest {
        val s = session()
        s.dapps.fromPage(1, "https://vote.example", """{"type":"aztec-wallet-discovery"}""")
        val (method, params) = pxe.calls.last()
        assertEquals("dappMessage", method)
        assertEquals("https://vote.example", params["origin"]!!.jsonPrimitive.content)
        assertEquals("0xme", params["account"]!!.jsonPrimitive.content)
    }

    @Test
    fun oversizedMessagesAndClosedPagesAreHandled() = runTest {
        val s = session()
        val before = pxe.calls.size
        s.dapps.fromPage(1, "https://x.example", "x".repeat(DappLink.MAX_MESSAGE + 1))
        assertEquals(before, pxe.calls.size)
        s.dapps.closePage(4)
        assertEquals("dappClosePage" to "4", pxe.calls.last().let { (m, p) -> m to p["tabId"]!!.jsonPrimitive.content })
    }

    @Test
    fun repliesAreRoutedPerTabAndSessionsFollowed() = runTest {
        val s = session()
        val toTab1 = async { s.dapps.toPages().first { it.first == 1 }.second }
        val follower = launch { s.dapps.followSessions() }
        advanceUntilIdle()
        pxe.events.emit(PxeEvent("dapp", Json.parseToJsonElement("""{"tabId":2,"message":{"x":2}}""")))
        pxe.events.emit(PxeEvent("dapp", Json.parseToJsonElement("""{"tabId":1,"message":{"x":1}}""")))
        pxe.events.emit(PxeEvent("dappSessions", Json.parseToJsonElement(
            """[{"sessionId":"s1","origin":"https://vote.example","appId":"vote","emojis":"🦊","granted":true}]""",
        )))
        advanceUntilIdle()
        assertEquals("""{"x":1}""", toTab1.await())
        assertEquals("https://vote.example", s.dapps.sessions.value.single().origin)
        follower.cancel()
    }

    @Test
    fun dappTransactionsLandInTheActivity() = runTest {
        val s = session()
        advanceUntilIdle()
        val tx = """"id":"t1","account":"0xme","origin":"https://vote.example","summary":"cast_vote on 0xab""""
        pxe.events.emit(PxeEvent("dappTx", Json.parseToJsonElement("""{$tx,"status":"proving"}""")))
        advanceUntilIdle()
        assertEquals(ActivityStatus.PROVING, s.state.value.activity.single().status)
        pxe.events.emit(PxeEvent("dappTx", Json.parseToJsonElement("""{$tx,"status":"sent","txHash":"0xhash"}""")))
        advanceUntilIdle()
        val item = s.state.value.activity.single()
        assertEquals(ActivityKind.DAPP, item.kind)
        assertEquals("cast_vote on 0xab", item.summary)
        assertEquals("https://vote.example", item.origin)
        assertEquals(ActivityStatus.CONFIRMED, item.status)
        assertEquals(42L, item.blockNumber)
    }

    @Test
    fun failedDappTransactionsKeepTheirError() = runTest {
        val s = session()
        advanceUntilIdle()
        val tx = """"id":"t2","account":"0xme","origin":"https://vote.example","summary":"vote""""
        pxe.events.emit(PxeEvent("dappTx", Json.parseToJsonElement("""{$tx,"status":"proving"}""")))
        pxe.events.emit(PxeEvent("dappTx", Json.parseToJsonElement("""{$tx,"status":"failed","error":"out of gas"}""")))
        advanceUntilIdle()
        val item = s.state.value.activity.single()
        assertEquals(ActivityStatus.FAILED, item.status)
        assertEquals("out of gas", item.error)
    }
}
