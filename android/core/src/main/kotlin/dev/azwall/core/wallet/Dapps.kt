package dev.azwall.core.wallet

import dev.azwall.core.pxe.PxeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

enum class ApprovalKind { CONNECT, VERIFY, CAPABILITIES, TRANSACTION, AUTHWIT }

/** Something a dApp asks the user, shown as a sheet over the page (pxe/<bundle>/src/dapp/gateway.ts). */
data class ApprovalRequest(
    val id: Long,
    val kind: ApprovalKind,
    val origin: String,
    val title: String,
    val details: List<String>,
    /** Shown by the page too once the secure channel is up: the user checks they match. */
    val emojis: String?,
)

/** The user's answers to dApp requests. One queue for the app; the PXE host's `approve` calls wait on it. */
class DappApprovals {
    private val nextId = AtomicLong(1)
    private val waiting = mutableMapOf<Long, CompletableDeferred<Boolean>>()
    private val _pending = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pending: StateFlow<List<ApprovalRequest>> = _pending.asStateFlow()

    /** Native handler for the host's `approve` call: params in, `{ approved }` out. */
    suspend fun approve(params: JsonObject): JsonElement {
        val kind = runCatching { ApprovalKind.valueOf(params.text("kind").uppercase()) }.getOrElse { throw PxeException("unknown approval kind") }
        val details = params["details"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val approved = ask(kind, params.text("origin"), params.text("title"), details, params["emojis"]?.jsonPrimitive?.content)
        return buildJsonObject { put("approved", approved) }
    }

    suspend fun ask(kind: ApprovalKind, origin: String, title: String, details: List<String>, emojis: String?): Boolean {
        val request = ApprovalRequest(nextId.getAndIncrement(), kind, origin, title, details, emojis)
        val answer = CompletableDeferred<Boolean>()
        synchronized(waiting) { waiting[request.id] = answer }
        _pending.update { it + request }
        return try {
            answer.await()
        } finally {
            synchronized(waiting) { waiting.remove(request.id) }
            _pending.update { list -> list.filterNot { it.id == request.id } }
        }
    }

    fun decide(id: Long, approved: Boolean) {
        synchronized(waiting) { waiting[id] }?.complete(approved)
    }

    /** Rejects everything pending, e.g. when the network changes and the host that asked is gone. */
    fun rejectAll() {
        synchronized(waiting) { waiting.values.toList() }.forEach { it.complete(false) }
    }
}

/** A site connected to the wallet through the browser. */
@Serializable
data class DappSession(val sessionId: String, val origin: String, val appId: String, val emojis: String, val granted: Boolean)

/**
 * The browser's link to the PXE host's dApp gateway: page messages go in with their origin (from the web view,
 * never from the page) and the selected account; the gateway's replies come out per tab.
 */
class DappLink internal constructor(private val session: WalletSession) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _sessions = MutableStateFlow<List<DappSession>>(emptyList())
    val sessions: StateFlow<List<DappSession>> = _sessions.asStateFlow()

    /** Messages for the content script of tab [tabId], as JSON strings. */
    fun toPage(tabId: Int): Flow<String> = session.pxeEvents
        .filter { it.name == "dapp" }
        .mapNotNull { e -> e.data.jsonObject.takeIf { it["tabId"]?.jsonPrimitive?.content == tabId.toString() }?.get("message")?.toString() }

    /** Keeps [sessions] in sync with the gateway; collect it while the browser is alive. */
    suspend fun followSessions() {
        session.pxeEvents.filter { it.name == "dappSessions" }.collect { e ->
            _sessions.value = runCatching { json.decodeFromJsonElement<List<DappSession>>(e.data) }.getOrDefault(emptyList())
        }
    }

    /** A message from the content script of the page at [origin] in tab [tabId]. */
    suspend fun fromPage(tabId: Int, origin: String, message: String) {
        val account = session.state.value.account?.address ?: return
        session.client.callRaw(
            "dappMessage",
            buildJsonObject { put("tabId", tabId); put("origin", origin); put("message", message); put("account", account) },
        )
    }

    suspend fun disconnect(sessionId: String) {
        session.client.callRaw("dappDisconnect", buildJsonObject { put("sessionId", sessionId) })
    }
}

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content ?: ""
