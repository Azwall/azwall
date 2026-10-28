package dev.azwall.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import dev.azwall.core.pxe.PxeHost
import dev.azwall.core.wallet.Browsing
import dev.azwall.core.wallet.SearchEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the address bar and the navigation buttons show. */
data class PageState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 100,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** The native start page (suggested dApps) shows instead of the web view. */
    val start: Boolean = true,
    /** Why the start page shows instead of the page: the page's renderer stopped. */
    val stopped: String? = null,
)

/** Receives what a tab's page sends to the wallet: its page id, its origin (from the web view) and the message. */
fun interface PageMessages {
    fun received(page: Int, origin: String, message: String)
}

/**
 * One browser tab: its own web view and history. Each page load gets a new page id (from [nextPage]), which the
 * dApp gateway ties its sessions to: when the tab loads another page, [onPageClosed] ends the previous page's
 * sessions and pending requests, and replies for it are no longer delivered.
 */
class BrowserTab(private val context: Context, val id: Int, private val nextPage: () -> Int, private val onPageClosed: (Int) -> Unit) {
    /** Replaced when its renderer stops: a web view whose renderer is gone cannot be used again. */
    var webView: WebView = createWebView(context)
        private set
    /** Counts the web views this tab had: the screen shows the new one when it changes. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()
    /** What [install] was last given, for a replacement web view. */
    private var installed: Pair<String, PageMessages>? = null
    private val _page = MutableStateFlow(PageState())
    val page: StateFlow<PageState> = _page.asStateFlow()

    /** The page now loaded; 0 before the first load. */
    var pageId = 0
        private set
    private var reply: Pair<Int, JavaScriptReplyProxy>? = null
    private var script: ScriptHandler? = null

    /** Installs the content script and the bridge; called again with another network's script. */
    @SuppressLint("RequiresFeature")
    fun install(contentScript: String, messages: PageMessages) {
        installed = contentScript to messages
        script?.remove()
        script = WebViewCompat.addDocumentStartJavaScript(webView, contentScript, setOf("*"))
        WebViewCompat.removeWebMessageListener(webView, BRIDGE)
        WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf("*")) { _, message, origin, isMainFrame, replyProxy ->
            val o = origin.toString()
            if (!isMainFrame || !BrowserTabs.allowedOrigin(o) || pageId == 0) return@addWebMessageListener
            val from = BrowserTabs.originOf(o)
            if (from != null && from == leftOrigin && from != pageOrigin) return@addWebMessageListener
            reply = pageId to replyProxy
            message.data?.let { messages.received(pageId, o, it) }
        }
    }

    /** Delivers a gateway message, only if [page] is still the page loaded. */
    @SuppressLint("RequiresFeature")
    fun deliver(page: Int, json: String) {
        reply?.takeIf { it.first == page && page == pageId }?.second?.postMessage(json)
    }

    /** Opens a URL, or searches the text with [engine] when it does not look like one; nothing typed: the start page. */
    fun open(input: String, engine: SearchEngine) {
        val url = Browsing.addressOf(input, engine) ?: return home()
        // Typed addresses skip shouldOverrideUrlLoading: the PXE's origin is refused here too.
        if (BrowserTabs.originOf(url) == "https://${PxeHost.HOST}") return home()
        _page.value = _page.value.copy(start = false, stopped = null)
        webView.loadUrl(url)
    }

    /** The start page; the web view keeps its page and history underneath. */
    fun home() {
        _page.value = _page.value.copy(start = true)
    }

    /** Back in the page's history, then out to the start page. */
    fun back() = if (webView.canGoBack()) webView.goBack() else home()

    /** From the start page, forward returns to the page left open; then on in its history. */
    fun forward() = if (_page.value.start && webView.url != null) _page.value = _page.value.copy(start = false) else webView.goForward()

    fun reload() = webView.reload()

    /**
     * The system ended the page's renderer (memory): without handling it, Android ends the whole app. The page ends
     * like on a navigation (its dApp sessions and pending requests with it), and a new web view takes the tab.
     */
    private fun rendererGone(view: WebView) {
        if (view !== webView) return
        val gone = pageId
        pageId = 0
        reply = null
        if (gone != 0) onPageClosed(gone)
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        script = null
        webView = createWebView(context)
        installed?.let { (contentScript, messages) -> install(contentScript, messages) }
        _page.value = PageState(stopped = "The page stopped: the system ended it, often to free memory. Open it again.")
        _generation.value++
    }

    /** The tab closes: its page ends like on a navigation. */
    fun destroy() {
        if (pageId != 0) onPageClosed(pageId)
        webView.destroy()
    }

    /** A new document in the main frame: a new page id, and the previous page's sessions end. */
    /**
     * The id changes when a navigation starts, before the new page replaces the old one: a message the old page sends
     * meanwhile must not pass for the new page's. Messages from the origin being left are dropped then (unless the
     * new page has the same origin); a redirect to a third origin is not affected.
     */
    private var pageOrigin: String? = null
    private var leftOrigin: String? = null

    private fun newPage(url: String? = null) {
        val previous = pageId
        pageId = nextPage()
        leftOrigin = pageOrigin
        pageOrigin = url?.let(BrowserTabs::originOf)
        reply = null
        if (previous != 0) onPageClosed(previous)
    }

    private fun update() {
        _page.value = _page.value.copy(
            url = webView.url.orEmpty(),
            title = webView.title.orEmpty(),
            canGoBack = webView.canGoBack(),
            canGoForward = webView.canGoForward(),
        )
    }

    // MissingOnRenderProcessGone: implemented below (rendererGone); the check does not see it in an anonymous object.
    @SuppressLint("SetJavaScriptEnabled", "MissingOnRenderProcessGone")
    private fun createWebView(context: Context) = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                newPage(url)
                update()
            }

            override fun onPageFinished(view: WebView, url: String) = update()

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                rendererGone(view)
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // The page committed (after any redirect): its own origin from now on, the old one is gone.
                pageOrigin = BrowserTabs.originOf(url)
                leftOrigin = null
                update()
            }

            /**
             * Only web pages: no intent://, file:// or other schemes leave the browser. Nor the PXE's own origin: the
             * tabs share the web view profile, and so that origin's storage, with the hidden PXE page.
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme !in setOf("https", "http") || request.url.host == PxeHost.HOST
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _page.value = _page.value.copy(progress = newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String) = update()
        }
    }

    private companion object {
        const val BRIDGE = "azwallBridge"
    }
}
