import AzwallKit
import Foundation
import Observation
import WebKit

/// The Explore tab's web view, kept for the whole unlocked app (it survives tab switches). Pages get the wallet-sdk
/// content script at document start and an `azwallBridge` object, both in the `azwall` content world: the page's
/// own scripts cannot see or replace them, and they still reach the page through window messages. The page's
/// origin comes from WebKit (frameInfo), never from the page.
@MainActor
@Observable
final class BrowserController: NSObject {
    static let home = URL(string: "https://www.google.com")!
    /// One tab for now; the gateway routes its replies by tab.
    private static let tab = 1
    private static let bridge = "azwallBridge"
    private static let world = WKContentWorld.world(name: "azwall")
    /// `azwallBridge` as the content script expects it (pxe/<bundle>/src/dapp/content_script.ts).
    private static let shim = """
    window.azwallBridge = { onmessage: null, postMessage(data) { window.webkit.messageHandlers.azwallBridge.postMessage(String(data)); } };
    """

    private(set) var url = ""
    private(set) var title = ""
    private(set) var progress = 1.0
    private(set) var canGoBack = false
    private(set) var canGoForward = false

    @ObservationIgnored let webView: WKWebView
    @ObservationIgnored private var observations: [NSKeyValueObservation] = []
    @ObservationIgnored private var link: DappLink?
    @ObservationIgnored private var relay: Task<Void, Never>?

    override init() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        #if DEBUG
        webView.isInspectable = true
        #endif
        webView.allowsBackForwardNavigationGestures = true
        observe()
    }

    /// Connects the browser to a network's dApp gateway (its content script, its PXE host). Called again when the
    /// network changes: the previous link is dropped.
    func attach(_ link: DappLink, contentScript: String) {
        let scripts = webView.configuration.userContentController
        scripts.removeAllUserScripts()
        scripts.removeScriptMessageHandler(forName: Self.bridge, contentWorld: Self.world)
        scripts.addUserScript(WKUserScript(source: Self.shim, injectionTime: .atDocumentStart, forMainFrameOnly: true, in: Self.world))
        scripts.addUserScript(WKUserScript(source: contentScript, injectionTime: .atDocumentStart, forMainFrameOnly: true, in: Self.world))
        scripts.add(WeakHandler(self), contentWorld: Self.world, name: Self.bridge)
        self.link = link
        relay?.cancel()
        relay = Task { [weak self] in
            for await json in link.toPage(Self.tab) { self?.deliver(json) }
        }
    }

    func open(_ input: String) {
        guard let target = Self.url(for: input) else { return }
        webView.load(URLRequest(url: target))
    }

    func back() { webView.goBack() }
    func forward() { webView.goForward() }
    func reload() { webView.reload() }

    fileprivate func received(_ message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame, let body = message.body as? String else { return }
        let origin = Self.origin(message.frameInfo.securityOrigin)
        guard Self.allowedOrigin(origin), let link else { return }
        Task { await link.fromPage(Self.tab, origin: origin, message: body) }
    }

    private func deliver(_ json: String) {
        let literal = (try? String(decoding: JSONEncoder().encode(json), as: UTF8.self)) ?? "\"\""
        let script = "window.azwallBridge && window.azwallBridge.onmessage && window.azwallBridge.onmessage({ data: \(literal) })"
        webView.evaluateJavaScript(script, in: nil, in: Self.world) { _ in }
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

    /// dApps must be served over https; plain http only from this device (dApp development).
    static func allowedOrigin(_ origin: String) -> Bool {
        guard let url = URL(string: origin), let host = url.host() else { return false }
        return url.scheme == "https" || (url.scheme == "http" && (host == "localhost" || host == "127.0.0.1"))
    }

    /// What the address bar opens: a URL as typed, a host (https), or a Google search.
    static func url(for input: String) -> URL? {
        let text = input.trimmingCharacters(in: .whitespaces)
        if text.isEmpty { return nil }
        if text.hasPrefix("http://") || text.hasPrefix("https://") { return URL(string: text) }
        if text.hasPrefix("localhost") || text.hasPrefix("127.0.0.1") { return URL(string: "http://\(text)") }
        if text.contains(".") && !text.contains(" ") { return URL(string: "https://\(text)") }
        let query = text.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? text
        return URL(string: "https://www.google.com/search?q=\(query)")
    }
}

/// WKUserContentController retains its handlers: this one holds the browser weakly.
@MainActor
private final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: BrowserController?

    init(_ target: BrowserController) {
        self.target = target
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.received(message)
    }
}
