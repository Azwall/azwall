package dev.azwall.app.ui.browser

import dev.azwall.app.BuildConfig
import android.content.Context
import androidx.webkit.WebViewFeature
import dev.azwall.core.DiagnosticLog
import dev.azwall.core.wallet.DappLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's browser: tabs kept alive for the whole unlocked app. Every main-frame page served over https (or http on
 * localhost) gets the wallet-sdk content script at document start and an `azwallBridge` object whose native side
 * knows the page's real origin. Messages go to the PXE host's dApp gateway through [DappLink] with their page id;
 * replies come back to the tab whose page they are for, if it is still loaded.
 */
class BrowserTabs(private val context: Context, private val onPageClosed: (Int) -> Unit) {
    private val tabIds = AtomicInteger(1)
    private val pageIds = AtomicInteger(1)
    private val _tabs = MutableStateFlow(listOf(newTab()))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()
    private val _current = MutableStateFlow(_tabs.value.first())
    val current: StateFlow<BrowserTab> = _current.asStateFlow()
    private var installed: Pair<String, PageMessages>? = null
    private var link: DappLink? = null
    private var links: Job? = null

    /** Connects every tab to a network's gateway: its content script, its PXE host. Replaces the previous one. */
    fun attach(link: DappLink, contentScript: String, scope: CoroutineScope) {
        check(supported()) { "this WebView is too old for dApps; update Android System WebView" }
        this.link = link
        // The dApp waits for a reply that does not come; reported so a stuck dApp can be diagnosed.
        val messages = PageMessages { page, origin, message -> scope.launch { runCatching { link.fromPage(page, origin, message) }.onFailure { DiagnosticLog.shared.report("dapps", it) } } }
        installed = contentScript to messages
        _tabs.value.forEach { it.install(contentScript, messages) }
        links?.cancel()
        links = scope.launch {
            launch { link.toPages().collect { (page, json) -> withContext(Dispatchers.Main) { tabOf(page)?.deliver(page, json) } } }
            launch { link.followSessions() }
        }
    }

    /** Opens a new tab on the start page and shows it. */
    fun open(): BrowserTab = newTab().also { tab ->
        _tabs.update { it + tab }
        _current.value = tab
    }

    fun select(tab: BrowserTab) {
        if (tab in _tabs.value) _current.value = tab
    }

    /** Shows the tab whose loaded page is [page], e.g. the one a dApp request comes from. */
    fun selectPage(page: Int) {
        tabOf(page)?.let(::select)
    }

    /** Closes a tab (its page's sessions end); the last one is replaced by a fresh tab. */
    fun close(tab: BrowserTab) {
        val left = _tabs.value - tab
        val next = left.ifEmpty { listOf(newTab()) }
        _tabs.value = next
        if (_current.value == tab) _current.value = next.last()
        tab.destroy()
    }

    private fun tabOf(page: Int) = _tabs.value.firstOrNull { it.pageId == page }

    private fun newTab() = BrowserTab(context, tabIds.getAndIncrement(), pageIds::getAndIncrement, onPageClosed).also { tab ->
        installed?.let { (script, messages) -> tab.install(script, messages) }
    }

    companion object {
        fun supported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        /**
         * dApps must be served over https; http on this device only in debug builds, for local development: in a
         * release, any app on the phone could serve a "dApp" there.
         */
        fun allowedOrigin(origin: String, local: Boolean = BuildConfig.DEBUG): Boolean {
            // An origin that does not parse is simply not allowed.
            val uri = runCatching { URI(origin) }.getOrNull() ?: return false
            return uri.scheme == "https" || (local && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1"))
        }
    }
}
