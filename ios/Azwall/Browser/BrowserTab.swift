import AzwallKit
import Foundation
import Observation
import WebKit

/// One browser tab: its own web view and history. Pages get the wallet-sdk content script at document start and an
/// `azwallBridge` object, both in the `azwall` content world: the page's own scripts cannot see or replace them,
/// and they still reach the page through window messages. The page's origin comes from WebKit (frameInfo), never
/// from the page. Each page load gets a new page id, which the dApp gateway ties its sessions to: when the tab
/// loads another page, `onPageClosed` ends the previous page's sessions and requests, and its replies are dropped.
@MainActor
@Observable
final class BrowserTab: NSObject, Identifiable {
    static let bridge = "azwallBridge"
    static let world = WKContentWorld.world(name: "azwall")
    /// `azwallBridge` as the content script expects it (pxe/<bundle>/src/dapp/content_script.ts).
    private static let shim = """
    window.azwallBridge = { onmessage: null, postMessage(data) { window.webkit.messageHandlers.azwallBridge.postMessage(String(data)); } };
    """

    let id: Int
    private(set) var url = ""
    private(set) var title = ""
    private(set) var progress = 1.0
    private(set) var canGoBack = false
    private(set) var canGoForward = false
    /// The native start page (suggested dApps) shows instead of the web view.
    private(set) var start = true
    /// The page now loaded; 0 before the first load.
    private(set) var pageId = 0

    @ObservationIgnored let webView: WKWebView
    @ObservationIgnored private var observations: [NSKeyValueObservation] = []
    @ObservationIgnored private let nextPage: () -> Int
    @ObservationIgnored private let onPageClosed: (Int) -> Void
    @ObservationIgnored var onMessage: ((Int, String, String) -> Void)?

    init(id: Int, nextPage: @escaping () -> Int, onPageClosed: @escaping (Int) -> Void) {
        self.id = id
        self.nextPage = nextPage
        self.onPageClosed = onPageClosed
        let config = WKWebViewConfiguration()
        config.websiteDataStore = BrowserTabs.dataStore
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        #if DEBUG
        webView.isInspectable = true
        #endif
        webView.allowsBackForwardNavigationGestures = true
        webView.navigationDelegate = self
        observe()
    }

    /// Installs a network's content script and the bridge; called again when the network changes.
    func install(contentScript: String) {
        let scripts = webView.configuration.userContentController
        scripts.removeAllUserScripts()
        scripts.removeScriptMessageHandler(forName: Self.bridge, contentWorld: Self.world)
        scripts.addUserScript(WKUserScript(source: Self.shim, injectionTime: .atDocumentStart, forMainFrameOnly: true, in: Self.world))
        scripts.addUserScript(WKUserScript(source: contentScript, injectionTime: .atDocumentStart, forMainFrameOnly: true, in: Self.world))
        scripts.add(WeakHandler(self), contentWorld: Self.world, name: Self.bridge)
    }

    /// Delivers a gateway message, only if `page` is still the page loaded.
    func deliver(page: Int, json: String) {
        guard page == pageId, page != 0 else { return }
        // Encoding a String as JSON cannot fail; the fallback only satisfies the compiler.
        let literal = (try? String(decoding: JSONEncoder().encode(json), as: UTF8.self)) ?? "\"\""
        let script = "window.azwallBridge && window.azwallBridge.onmessage && window.azwallBridge.onmessage({ data: \(literal) })"
        webView.evaluateJavaScript(script, in: nil, in: Self.world) { _ in }
    }

    /// Opens a URL, or searches the text with the saved engine; nothing typed: the start page.
    func open(_ input: String) {
        let engine = SearchEngine.named(UserDefaults.standard.string(forKey: BrowserTabs.engineKey))
        guard let address = Browsing.address(of: input, engine: engine), let target = URL(string: address) else { return home() }
        start = false
        webView.load(URLRequest(url: target))
    }

    /// The start page; the web view keeps its page and history underneath.
    func home() { start = true }

    /// Back in the page's history, then out to the start page.
    func back() { if webView.canGoBack { webView.goBack() } else { home() } }

    /// From the start page, forward returns to the page left open; then on in its history.
    func forward() { if start && webView.url != nil { start = false } else { webView.goForward() } }

    func reload() { webView.reload() }

    /// The tab closes: its page ends like on a navigation.
    func close() {
        if pageId != 0 { onPageClosed(pageId) }
        webView.stopLoading()
    }

    fileprivate func received(_ message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame, pageId != 0, let body = message.body as? String else { return }
        let origin = Self.origin(message.frameInfo.securityOrigin)
        guard BrowserTabs.allowedOrigin(origin) else { return }
        onMessage?(pageId, origin, body)
    }

    /// A new document in the main frame: a new page id, and the previous page's sessions end.
    fileprivate func committed() {
        let previous = pageId
        pageId = nextPage()
        if previous != 0 { onPageClosed(previous) }
    }

    private func observe() {
        observations = [
            webView.observe(\.url) { [weak self] web, _ in MainActor.assumeIsolated { self?.url = web.url?.absoluteString ?? "" } },
            webView.observe(\.title) { [weak self] web, _ in MainActor.assumeIsolated { self?.title = web.title ?? "" } },
            webView.observe(\.estimatedProgress) { [weak self] web, _ in MainActor.assumeIsolated { self?.progress = web.estimatedProgress } },
            webView.observe(\.canGoBack) { [weak self] web, _ in MainActor.assumeIsolated { self?.canGoBack = web.canGoBack } },
            webView.observe(\.canGoForward) { [weak self] web, _ in MainActor.assumeIsolated { self?.canGoForward = web.canGoForward } },
        ]
    }

    private static func origin(_ o: WKSecurityOrigin) -> String {
        let standard = (o.protocol == "https" && o.port == 443) || (o.protocol == "http" && o.port == 80) || o.port == 0
        return standard ? "\(o.protocol)://\(o.host)" : "\(o.protocol)://\(o.host):\(o.port)"
    }
}

extension BrowserTab: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) { committed() }

    /// Only web pages: no custom schemes leave the browser for another app.
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        let scheme = action.request.url?.scheme ?? ""
        decisionHandler(["https", "http", "about", "blob", "data"].contains(scheme) ? .allow : .cancel)
    }
}

/// WKUserContentController retains its handlers: this one holds the tab weakly.
@MainActor
private final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: BrowserTab?

    init(_ target: BrowserTab) {
        self.target = target
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.received(message)
    }
}
