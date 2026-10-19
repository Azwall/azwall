package dev.azwall.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.azwall.core.wallet.DappLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder

/** What the address bar and the navigation buttons show. */
data class PageState(val url: String = "", val title: String = "", val progress: Int = 100, val canGoBack: Boolean = false, val canGoForward: Boolean = false)

/**
 * The app's browser: one web view, kept alive across tabs. Every main-frame page served over https (or http on
 * localhost) gets the wallet-sdk content script at document start and an `azwallBridge` object whose native side
 * knows the page's real origin. Messages go to the PXE host's dApp gateway through [DappLink]; replies come back
 * to the page that sent the last message.
 */
class BrowserController(context: Context) {
    val webView: WebView = createWebView(context)
    private val _page = MutableStateFlow(PageState())
    val page: StateFlow<PageState> = _page.asStateFlow()
    private var reply: JavaScriptReplyProxy? = null
    private var script: ScriptHandler? = null
    private var links: Job? = null

    /** Connects the browser to a network's gateway: its content script, its PXE host. Replaces the previous one. */
    @SuppressLint("RequiresFeature")
    fun attach(link: DappLink, contentScript: String, scope: CoroutineScope) {
        check(supported()) { "this WebView is too old for dApps; update Android System WebView" }
        script?.remove()
        script = WebViewCompat.addDocumentStartJavaScript(webView, contentScript, setOf("*"))
        WebViewCompat.removeWebMessageListener(webView, BRIDGE)
        WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf("*")) { _, message, origin, isMainFrame, replyProxy ->
            val o = origin.toString()
            if (!isMainFrame || !allowedOrigin(o)) return@addWebMessageListener
            reply = replyProxy
            val data = message.data ?: return@addWebMessageListener
            scope.launch { runCatching { link.fromPage(TAB, o, data) } }
        }
        links?.cancel()
        links = scope.launch {
            launch { link.toPage(TAB).collect { json -> withContext(Dispatchers.Main) { reply?.postMessage(json) } } }
            launch { link.followSessions() }
        }
    }

    /** Opens a URL, or searches the text with Google when it does not look like one. */
    fun open(input: String) {
        webView.loadUrl(toUrl(input))
    }

    fun back() = webView.goBack()

    fun forward() = webView.goForward()

    fun reload() = webView.reload()

    private fun update() {
        _page.value = _page.value.copy(
            url = webView.url.orEmpty(),
            title = webView.title.orEmpty(),
            canGoBack = webView.canGoBack(),
            canGoForward = webView.canGoForward(),
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context) = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = update()

            override fun onPageFinished(view: WebView, url: String) = update()

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = update()

            /** Only web pages: no intent://, file:// or other schemes leave the browser. */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme !in setOf("https", "http")
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _page.value = _page.value.copy(progress = newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String) = update()
        }
    }

    companion object {
        const val HOME = "https://www.google.com"
        private const val BRIDGE = "azwallBridge"
        private const val TAB = 1

        fun supported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        /** dApps must be served over https; http only for local development. */
        fun allowedOrigin(origin: String): Boolean {
            val uri = runCatching { URI(origin) }.getOrNull() ?: return false
            return uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1"))
        }

        /** "example.org" → https://example.org; "aztec voting dapp" → a Google search. */
        fun toUrl(input: String): String {
            val text = input.trim()
            return when {
                text.startsWith("https://") || text.startsWith("http://") -> text
                text.isEmpty() -> HOME
                text.startsWith("localhost") || text.startsWith("127.0.0.1") -> "http://$text"
                ' ' !in text && '.' in text -> "https://$text"
                else -> "https://www.google.com/search?q=" + URLEncoder.encode(text, Charsets.UTF_8)
            }
        }
    }
}
