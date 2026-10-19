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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DappApprovalsTest {
    @Test
    fun approveWaitsForTheUserDecision() = runTest {
        val approvals = DappApprovals()
        val params = Json.parseToJsonElement(
            """{"kind":"capabilities","origin":"https://vote.example","title":"Vote asks for access","details":["accounts"],"emojis":"🦊🌵"}""",
        ).jsonObject
        val reply = async { approvals.approve(params) }
        advanceUntilIdle()
        val request = approvals.pending.value.single()
        assertEquals(ApprovalKind.CAPABILITIES, request.kind)
        assertEquals("🦊🌵", request.emojis)
        approvals.decide(request.id, true)
        assertTrue(reply.await().jsonObject["approved"]!!.jsonPrimitive.boolean)
        assertTrue(approvals.pending.value.isEmpty())
    }

    @Test
    fun rejectAllAnswersNo() = runTest {
        val approvals = DappApprovals()
        val answer = async { approvals.ask(ApprovalKind.TRANSACTION, "https://a", "Send?", emptyList(), null) }
        advanceUntilIdle()
        approvals.rejectAll()
        assertFalse(answer.await())
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
            else -> "null"
        }
        return Json.parseToJsonElement(reply)
    }
}

class DappLinkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pxe = GatewayPxe()

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
        return WalletSession(Backend, launcher, vault, contents, tmp.root, network, backgroundScope).apply { open() }
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
    fun repliesAreRoutedPerTabAndSessionsFollowed() = runTest {
        val s = session()
        val toTab1 = async { s.dapps.toPage(1).first() }
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
}
