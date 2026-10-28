package dev.azwall.core.pxe

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import dev.azwall.core.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs one PXE host bundle (`assets/pxe/<bundle>/`) in a web view the user never sees. The page is served
 * from the APK over https://appassets.androidplatform.net, a secure context, which the PXE's crypto and
 * its encrypted SQLite-OPFS stores require. One host runs at a time: the selected network's.
 */
class PxeHost private constructor(
    val bundle: String,
    private val webView: WebView,
    val channel: PxeChannel,
    private val scope: CoroutineScope,
) {
    /** Aztec version reported by the page once loaded. */
    var aztecVersion: String = ""
        private set

    fun destroy() {
        channel.close("the PXE host was stopped")
        scope.cancel()
        main.post { webView.destroy() }
    }

    companion object {
        private const val TAG = "AzwallPxe"
        private const val HOST = "appassets.androidplatform.net"
        private const val READY_TIMEOUT_MS = 60_000L
        private val main = Handler(Looper.getMainLooper())

        /** Loads the bundle and returns once the page reported `ready`. */
        suspend fun start(context: Context, bundle: String, handlers: Map<String, NativeHandler>): PxeHost {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            lateinit var webView: WebView
            val channel = PxeChannel({ json -> main.post { webView.evaluateJavascript("azwallHost.receive(${JsonPrimitive(json)})", null) } }, handlers, scope)
            // Subscribe before the page loads: events are not replayed.
            // Release builds keep warnings and errors only: info lines carry addresses and transaction hashes.
            val verbose = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            scope.launch(start = CoroutineStart.UNDISPATCHED) { channel.events.collect { logEvent(it, verbose) } }
            val ready = scope.async(start = CoroutineStart.UNDISPATCHED) { channel.events.first { it.name == "ready" } }
            webView = withContext(Dispatchers.Main) { createWebView(context.applicationContext, channel, bundle) }
            val host = PxeHost(bundle, webView, channel, scope)
            val event = try {
                withTimeout(READY_TIMEOUT_MS) { ready.await() }
            } catch (e: Exception) {
                host.destroy()
                throw PxeException("the PXE host $bundle did not start: ${e.message}")
            }
            // A host that does not say its Aztec version leaves the line blank in Settings; reported as a host/app mismatch.
            host.aztecVersion = runCatching { event.data.jsonObject["aztec"]!!.jsonPrimitive.content }.onFailure { DiagnosticLog.shared.report("pxe", it) }.getOrDefault("")
            return host
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun createWebView(context: Context, channel: PxeChannel, bundle: String): WebView {
            val loader = WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            return WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // The system may end the web view's process (memory): the app goes on, the host is stopped.
                webViewClient = AssetClient(loader) { channel.close("the wallet engine stopped: the system ended it, often to free memory") }
                addJavascriptInterface(Bridge(channel), "AzwallAndroid")
                loadUrl("https://$HOST/assets/pxe/$bundle/index.html")
            }
        }

        private fun logEvent(event: PxeEvent, verbose: Boolean) {
            if (event.name != "log") return
            // A log that is not an object is still logged, as its raw text.
            val data = runCatching { event.data.jsonObject }.getOrNull()
            val message = data?.get("message")?.jsonPrimitive?.content ?: event.data.toString()
            val scope = data?.get("scope")?.jsonPrimitive?.content ?: "pxe"
            when (data?.get("level")?.jsonPrimitive?.content) {
                "error" -> Log.e(TAG, "[$scope] $message").also { DiagnosticLog.shared.record(DiagnosticLog.Level.ERROR, "pxe $scope", message) }
                "warn" -> Log.w(TAG, "[$scope] $message").also { DiagnosticLog.shared.record(DiagnosticLog.Level.WARN, "pxe $scope", message) }
                else -> if (verbose) Log.i(TAG, "[$scope] $message")
            }
        }
    }

    /** Serves the bundle from the APK and keeps the page on its own origin. */
    private class AssetClient(private val loader: WebViewAssetLoader, private val onGone: () -> Unit) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            loader.shouldInterceptRequest(request.url)?.also { response ->
                // Streaming compilation requires the exact WASM MIME type.
                if (request.url.path.orEmpty().endsWith(".wasm")) response.mimeType = "application/wasm"
            }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        /** Without this, Android kills the whole app when the web view's renderer dies. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onGone()
            view.destroy()
            return true
        }
    }

    /** `AzwallAndroid.postMessage(json)` in the page. Called on a WebView background thread. */
    private class Bridge(private val channel: PxeChannel) {
        @JavascriptInterface
        fun postMessage(json: String) = channel.receive(json)
    }
}
