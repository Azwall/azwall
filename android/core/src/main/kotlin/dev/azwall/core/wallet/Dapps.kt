package dev.azwall.core.wallet

import dev.azwall.core.DiagnosticLog
import dev.azwall.core.pxe.PxeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

enum class ApprovalKind {
    CONNECT, VERIFY, CAPABILITIES, TRANSACTION, AUTHWIT;

    /** Approving it spends or lets spend from the account: the owner confirms it (fingerprint), as for the wallet's own. */
    val signs: Boolean get() = this == TRANSACTION || this == AUTHWIT
}

/** Something a dApp asks the user, shown as a sheet over the page (pxe/<bundle>/src/dapp/gateway.ts). */
data class ApprovalRequest(
    val id: Long,
    val kind: ApprovalKind,
    /** The page that asked (the browser's page id): its requests are dropped when it navigates away or closes. */
    val tab: Int,
    val origin: String,
    val title: String,
    val details: List<String>,
    /** Shown by the page too once the secure channel is up: the user checks they match. */
    val emojis: String?,
    /** Everything else, in full (contracts, functions, calls, arguments, fees, the raw request), for the detail view. */
    val sections: List<ApprovalSection> = emptyList(),
)

/** A titled group of labelled values in an approval's detail view (pxe/<bundle>/src/dapp/details.ts). */
data class ApprovalSection(val title: String, val rows: List<Row>) {
    data class Row(val label: String, val value: String)

    companion object {
        /** The sections of an `approve` call; malformed ones are skipped. */
        // Sections are only extra detail on top of the approval's title and details; a malformed list shows none.
        fun list(json: JsonElement?): List<ApprovalSection> = runCatching { json?.jsonArray }.getOrNull().orEmpty().mapNotNull { section ->
            val o = section as? JsonObject ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            // A section without readable rows is skipped with the rest of what is malformed.
            val rows = runCatching { o["rows"]?.jsonArray }.getOrNull().orEmpty().mapNotNull { row ->
                val r = row as? JsonObject ?: return@mapNotNull null
                val label = r["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val value = r["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Row(label, value)
            }
            ApprovalSection(title, rows)
        }
    }
}

/** The user's answers to dApp requests. One queue for the app; the PXE host's `approve` calls wait on it. */
class DappApprovals {
    private val nextId = AtomicLong(1)
    private val waiting = mutableMapOf<Long, CompletableDeferred<Boolean>>()
    private val _pending = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pending: StateFlow<List<ApprovalRequest>> = _pending.asStateFlow()

    /** Native handler for the host's `approve` call: params in, `{ approved }` out. */
    suspend fun approve(params: JsonObject): JsonElement {
        // Not dropped: rethrown, so the host's call fails and nothing is approved.
        val kind = runCatching { ApprovalKind.valueOf(params.text("kind").uppercase()) }.getOrElse { throw PxeException("unknown approval kind") }
        val details = params["details"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val tab = params["tab"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val emojis = params["emojis"]?.jsonPrimitive?.content
        val approved = ask(kind, tab, params.text("origin"), params.text("title"), details, emojis, ApprovalSection.list(params["sections"]))
        return buildJsonObject { put("approved", approved) }
    }

    suspend fun ask(
        kind: ApprovalKind,
        tab: Int,
        origin: String,
        title: String,
        details: List<String>,
        emojis: String?,
        sections: List<ApprovalSection> = emptyList(),
    ): Boolean {
        val request = ApprovalRequest(nextId.getAndIncrement(), kind, tab, origin, title, details, emojis, sections)
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

    private val answering = mutableSetOf<Long>()

    /**
     * The user's answer to [request]. A spend ([ApprovalKind.signs]) is approved only once [owner] confirms it
     * (fingerprint) — declined, the request stays pending to retry or reject — and after [settle] (a cancelled wallet
     * transaction winding down). A second tap while the first is being confirmed is ignored.
     */
    suspend fun answer(request: ApprovalRequest, approved: Boolean, owner: suspend () -> Boolean, settle: suspend () -> Unit) {
        if (!synchronized(answering) { answering.add(request.id) }) return
        try {
            if (approved && request.kind.signs) {
                if (!owner()) return
                settle()
            }
            decide(request.id, approved)
        } finally {
            synchronized(answering) { answering.remove(request.id) }
        }
    }

    fun decide(id: Long, approved: Boolean) {
        synchronized(waiting) { waiting[id] }?.complete(approved)
    }

    /** Rejects what page [tab] asked: it navigated away or closed, an answer must not reach the next page. */
    fun rejectTab(tab: Int) {
        val ids = _pending.value.filter { it.tab == tab }.map { it.id }
        synchronized(waiting) { ids.mapNotNull { waiting[it] } }.forEach { it.complete(false) }
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

    /** Messages for the pages' content scripts: the page id each is for, and the message as JSON. */
    fun toPages(): Flow<Pair<Int, String>> = session.pxeEvents
        .filter { it.name == "dapp" }
        .mapNotNull { e ->
            val o = e.data.jsonObject
            val tab = o["tabId"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            o["message"]?.let { tab to it.toString() }
        }

    /** Keeps [sessions] in sync with the gateway; collect it while the browser is alive. */
    suspend fun followSessions() {
        session.pxeEvents.filter { it.name == "dappSessions" }.collect { e ->
            // A list the app cannot read shows no sessions; reported, as it means the host and app disagree on its shape.
            _sessions.value = runCatching { json.decodeFromJsonElement<List<DappSession>>(e.data) }.onFailure { DiagnosticLog.shared.report("dapps", it) }.getOrDefault(emptyList())
        }
    }

    /**
     * Records the transactions dApps send (the gateway's `dappTx` events) in the activity, like the wallet's own:
     * PROVING once approved, PENDING with the hash once sent (then followed to a block), or FAILED.
     */
    suspend fun followActivity() {
        val started = mutableMapOf<String, ActivityItem>()
        session.pxeEvents.filter { it.name == "dappTx" }.collect { e ->
            // An unreadable dApp transaction is missing from the activity; reported, as the host and app disagree on its shape.
            val tx = runCatching { json.decodeFromJsonElement<DappTx>(e.data) }.onFailure { DiagnosticLog.shared.report("dapps", it) }.getOrNull() ?: return@collect
            // The stored item first: it may hold the hash told before sending (Transactions.followSubmissions).
            val item = (session.store.item(tx.id) ?: started[tx.id] ?: tx.draft()).let(tx::applyTo)
            if (item.status == ActivityStatus.PROVING) started[tx.id] = item else started.remove(tx.id)
            session.recordActivity(item)
            if (item.status == ActivityStatus.PENDING) session.scope.launch { session.transactions.confirm(item) }
        }
    }

    /** A message from the content script of page [tabId] at [origin]; oversized ones are dropped. */
    suspend fun fromPage(tabId: Int, origin: String, message: String) {
        if (message.length > MAX_MESSAGE) return
        val account = session.state.value.account?.address ?: return
        session.client.callRaw(
            "dappMessage",
            buildJsonObject { put("tabId", tabId); put("origin", origin); put("message", message); put("account", account) },
        )
    }

    /** Page [tabId] navigated away or its tab closed: its sessions and pending connections end. */
    suspend fun closePage(tabId: Int) {
        session.client.callRaw("dappClosePage", buildJsonObject { put("tabId", tabId) })
    }

    suspend fun disconnect(sessionId: String) {
        session.client.callRaw("dappDisconnect", buildJsonObject { put("sessionId", sessionId) })
    }

    companion object {
        /**
         * `registerContract` carries a whole contract artifact, encrypted: several megabytes for a token (Palmyr's are
         * 4 to 9 MB). The cap only stops a page from wearing the PXE down with far larger messages.
         */
        const val MAX_MESSAGE = 32_000_000
    }
}

/** One step of a dApp transaction, as the gateway reports it. */
@Serializable
private data class DappTx(
    val id: String,
    val account: String,
    val origin: String,
    val summary: String,
    val status: String,
    val txHash: String? = null,
    val error: String? = null,
) {
    fun draft() = ActivityItem(
        id = id,
        account = account,
        kind = ActivityKind.DAPP,
        status = ActivityStatus.PROVING,
        createdAt = System.currentTimeMillis(),
        origin = origin,
        summary = summary,
    )

    /**
     * A hash known for the item (told before sending) is never dropped, and with one the item is pending until its
     * receipt says how it ended, even when the send failed after it: it may be mined. A sent transaction without any hash
     * cannot be followed: it is shown as done rather than pending forever.
     */
    fun applyTo(item: ActivityItem): ActivityItem {
        val hash = txHash ?: item.txHash
        return when (status) {
            "sent" -> item.copy(status = if (hash != null) ActivityStatus.PENDING else ActivityStatus.CONFIRMED, txHash = hash)
            "failed" -> if (hash != null) item.copy(status = ActivityStatus.PENDING, txHash = hash) else item.copy(status = ActivityStatus.FAILED, error = error)
            else -> item
        }
    }
}

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content ?: ""
