package dev.azwall.core.pxe

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PxeChannelTest {
    private val sent = mutableListOf<JsonObject>()

    private fun TestScope.channel(handlers: Map<String, NativeHandler> = emptyMap()) =
        PxeChannel({ sent += Json.parseToJsonElement(it).jsonObject }, handlers, this)

    private fun reply(id: Int, ok: Boolean, value: String) =
        if (ok) """{"kind":"reply","id":$id,"ok":true,"result":$value}""" else """{"kind":"reply","id":$id,"ok":false,"error":"$value"}"""

    @Test
    fun callsResolveWithTheHostResult() = runTest {
        val ch = channel()
        val result = async { ch.call("blockNumber") }
        advanceUntilIdle()
        val call = sent.single()
        assertEquals("call", call["kind"]!!.jsonPrimitive.content)
        assertEquals("blockNumber", call["method"]!!.jsonPrimitive.content)
        ch.receive(reply(call["id"]!!.jsonPrimitive.int, true, "7678"))
        assertEquals(7678, result.await().jsonPrimitive.int)
    }

    @Test
    fun hostErrorsBecomeExceptionsNamingTheMethod() = runTest {
        val ch = channel()
        val result = async { runCatching { ch.call("open") } }
        advanceUntilIdle()
        ch.receive(reply(sent.single()["id"]!!.jsonPrimitive.int, false, "network is not compatible"))
        assertEquals("open: network is not compatible", result.await().exceptionOrNull()?.message)
    }

    @Test
    fun hostCallsReachNativeHandlers() = runTest {
        val ch = channel(mapOf("prove" to { p -> buildJsonObject { put("response", "echo:" + p["request"]!!.jsonPrimitive.content) } }))
        ch.receive("""{"kind":"call","id":9,"method":"prove","params":{"request":"AA=="}}""")
        ch.receive("""{"kind":"call","id":10,"method":"nope","params":{}}""")
        advanceUntilIdle()
        val replies = sent.associateBy { it["id"]!!.jsonPrimitive.int }
        assertEquals("echo:AA==", replies[9]!!["result"]!!.jsonObject["response"]!!.jsonPrimitive.content)
        assertEquals("false", replies[10]!!["ok"]!!.jsonPrimitive.content)
        assertTrue(replies[10]!!["error"]!!.jsonPrimitive.content.contains("unknown native method"))
    }

    @Test
    fun eventsAreForwarded() = runTest {
        val ch = channel()
        val event = async { ch.events.first() }
        advanceUntilIdle()
        ch.receive("""{"kind":"event","name":"ready","data":{"aztec":"6.0.0-rc.3"}}""")
        assertEquals(PxeEvent("ready", buildJsonObject { put("aztec", "6.0.0-rc.3") }), event.await())
    }

    @Test
    fun closingFailsPendingAndFutureCalls() = runTest(StandardTestDispatcher()) {
        val ch = channel()
        val pending = async { runCatching { ch.call("balances") } }
        advanceUntilIdle()
        ch.close("web view destroyed")
        assertEquals("balances: web view destroyed", pending.await().exceptionOrNull()?.message)
        try {
            ch.call("blockNumber")
            fail("expected an exception")
        } catch (e: PxeException) {
            assertEquals("blockNumber: web view destroyed", e.message)
        }
    }

    @Test
    fun unreadableMessagesAreReportedNotThrown() = runTest {
        val ch = channel()
        val event = async { ch.events.first() }
        advanceUntilIdle()
        ch.receive("{oops")
        assertEquals("log", event.await().name)
        assertTrue((event.await().data as JsonPrimitive).content.startsWith("unreadable"))
    }
}
