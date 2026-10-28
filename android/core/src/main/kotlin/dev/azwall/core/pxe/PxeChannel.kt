package dev.azwall.core.pxe

import dev.azwall.core.DiagnosticLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A notification from the PXE host: `log`, `ready`, `proved`… */
data class PxeEvent(val name: String, val data: JsonElement)

class PxeException(message: String) : Exception(message)

/** Methods the PXE host may call on the app (today: `prove`). */
typealias NativeHandler = suspend (params: JsonObject) -> JsonElement

/**
 * The app side of the PXE host bridge (pxe/shared/src/bridge.ts): one JSON message per string, in both
 * directions (`call`, `reply`, `event`). [send] delivers a message to the page; the page's messages come
 * in through [receive]. Transport-agnostic, so it is tested without a web view.
 */
class PxeChannel(
    private val send: (String) -> Unit,
    private val handlers: Map<String, NativeHandler>,
    private val scope: CoroutineScope,
    /** How long a call may go unanswered; null waits for good. */
    private val timeoutOf: (method: String) -> Long? = ::defaultTimeout,
) {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pair<String, CompletableDeferred<JsonElement>>>()
    private val _events = MutableSharedFlow<PxeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<PxeEvent> = _events.asSharedFlow()

    @Volatile
    private var closedReason: String? = null

    /**
     * Calls a PXE host method and returns its result, or throws [PxeException] with the host's error. A call the
     * host never answers (a reply lost, a page stuck) fails after [timeoutOf] its method, rather than leaving the
     * screen waiting for good.
     */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JsonElement>()
        pending[id] = method to reply
        // Checked after the call is listed: a close() in between fails it rather than leaving it pending.
        closedReason?.let { pending.remove(id); throw PxeException("$method: $it") }
        send(message("call", id) { put("method", method); put("params", params) })
        val limit = timeoutOf(method)
        return try {
            if (limit == null) reply.await() else withTimeout(limit) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw PxeException("$method: no answer from the wallet engine after ${limit!! / 60_000} min")
        } finally {
            pending.remove(id)
        }
    }

    /** Handles one message from the page. Unreadable messages are reported as `log` events. */
    fun receive(json: String) {
        // Reported and emitted as a log event: the message is dropped, its call times out on the host side.
        val msg = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrElse {
            DiagnosticLog.shared.report("pxe", it)
            _events.tryEmit(PxeEvent("log", JsonPrimitive("unreadable message from the PXE host: ${it.message}")))
            return
        }
        when (msg["kind"]?.jsonPrimitive?.content) {
            "reply" -> settle(msg)
            "call" -> serve(msg)
            "event" -> _events.tryEmit(PxeEvent(msg.string("name"), msg["data"] ?: JsonNull))
        }
    }

    /** Fails every pending and future call, e.g. when the web view is destroyed, and says so with a `closed` event. */
    fun close(reason: String) {
        if (closedReason != null) return
        closedReason = reason
        // One by one: a call listed meanwhile is failed too, or sees closedReason itself.
        for (id in pending.keys.toList()) pending.remove(id)?.let { (method, reply) -> reply.completeExceptionally(PxeException("$method: $reason")) }
        _events.tryEmit(PxeEvent(CLOSED, JsonPrimitive(reason)))
    }

    private fun settle(msg: JsonObject) {
        val id = msg["id"]?.jsonPrimitive?.int ?: return
        val (method, reply) = pending.remove(id) ?: return
        if (msg["ok"]?.jsonPrimitive?.content == "true") {
            reply.complete(msg["result"] ?: JsonNull)
        } else {
            reply.completeExceptionally(PxeException("$method: ${msg.string("error")}"))
        }
    }

    private fun serve(msg: JsonObject) {
        val id = msg["id"]?.jsonPrimitive?.int ?: return
        val method = msg.string("method")
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        scope.launch {
            val handler = handlers[method]
            // Not dropped: a failure goes back to the host as the reply's error, for its caller to handle.
            val result = runCatching { handler?.invoke(params) ?: throw PxeException("unknown native method: $method") }
            send(
                message("reply", id) {
                    result.fold(
                        onSuccess = { put("ok", true); put("result", it) },
                        onFailure = { put("ok", false); put("error", it.message ?: it.javaClass.simpleName) },
                    )
                },
            )
        }
    }

    companion object {
        /** The event [close] emits, with its reason. */
        const val CLOSED = "closed"

        /** Methods that prove, wait for a block or open a network: minutes on a phone. */
        private val LONG = setOf("open", "transfer", "deployAccount", "deployToken", "mint", "claimFeeJuice", "waitForTx", "close", "flush")

        /** How long a call may go unanswered: reads are quick, proving and waiting for a block are not. */
        fun defaultTimeout(method: String): Long = if (method in LONG) 20 * 60_000L else 2 * 60_000L
    }

    private fun message(kind: String, id: Int, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        buildJsonObject { put("kind", kind); put("id", id); body() }.toString()
}

private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""
