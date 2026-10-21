import AzwallKit
import Foundation
import OSLog
import WebKit

/// Runs one PXE host bundle (`pxe/<bundle>/` in the app's resources) in a web view the user never sees.
/// Native code talks to it through `azwallHost.receive` and the `azwall` script message handler.
@MainActor
public final class PxeHost {
    public nonisolated let bundle: String
    public nonisolated let channel: PxeChannel
    private let webView: WKWebView

    private init(bundle: String, channel: PxeChannel, webView: WKWebView) {
        self.bundle = bundle
        self.channel = channel
        self.webView = webView
    }

    private static var servers: [String: BundleServer] = [:]
    private nonisolated static let readyTimeout: Duration = .seconds(60)

    /// Loads the bundle and returns once the page reported `ready`.
    public static func start(bundle: String, resources: URL, handlers: [String: NativeHandler]) async throws -> PxeHost {
        let server = try await server(for: bundle, resources: resources)
        let box = WebViewBox()
        let channel = PxeChannel(send: { json in Task { @MainActor in box.deliver(json) } }, handlers: handlers)
        let events = await channel.events()
        PxeLog.follow(await channel.events(), bundle: bundle)
        let keepsStores = handlers["storeSave"] != nil
        let webView = makeWebView(channel: channel, origin: "http://127.0.0.1:\(server.port)", keepsStores: keepsStores)
        box.webView = webView
        #if canImport(UIKit)
        OnScreen.attach(webView)
        #endif
        webView.load(URLRequest(url: URL(string: "http://127.0.0.1:\(server.port)/pxe/\(bundle)/index.html")!))
        let host = PxeHost(bundle: bundle, channel: channel, webView: webView)
        do {
            try await waitForReady(events)
        } catch {
            await host.stop()
            throw PxeError("the PXE host \(bundle) did not start: \(error.localizedDescription)")
        }
        return host
    }

    /// Unloads the page, not only its loading: a page left alive keeps running its PXE and holding its OPFS files,
    /// and the next host of the same bundle (same origin) would find them locked.
    public func stop() async {
        await channel.close("the PXE host was stopped")
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "azwall")
        webView.stopLoading()
        webView.loadHTMLString("", baseURL: nil)
        #if canImport(UIKit)
        OnScreen.detach(webView)
        #endif
    }

    private static func server(for bundle: String, resources: URL) async throws -> BundleServer {
        if let running = servers[bundle] { return running }
        let server = try BundleServer(root: resources, port: port(for: bundle))
        try await server.start()
        servers[bundle] = server
        return server
    }

    /// A fixed port per bundle keeps the page's origin, and so its OPFS stores, stable: v6 → 47606.
    static func port(for bundle: String) -> UInt16 {
        let n = UInt16(bundle.drop { !$0.isNumber }) ?? UInt16(bundle.unicodeScalars.reduce(0) { ($0 + Int($1.value)) % 90 })
        return 47_600 + min(n, 99)
    }

    private static func makeWebView(channel: PxeChannel, origin: String, keepsStores: Bool) -> WKWebView {
        let config = WKWebViewConfiguration()
        if keepsStores {
            // Tells the page, before it runs, to keep its stores in memory and hand them to the app (PxeStoreFiles).
            let flag = WKUserScript(source: "globalThis.azwallNativeStore = true;", injectionTime: .atDocumentStart, forMainFrameOnly: true)
            config.userContentController.addUserScript(flag)
        }
        config.websiteDataStore = .default()
        // The PXE's web view is never seen (OnScreen), and WebKit suspends an inactive page: its timers, workers and
        // promise chains stop between native calls (creating the PXE never finished). Keep it scheduled.
        config.preferences.inactiveSchedulingPolicy = .none
        config.userContentController.add(MessageHandler(channel: channel, origin: origin), name: "azwall")
        let webView = WKWebView(frame: .zero, configuration: config)
        #if DEBUG
        if #available(iOS 16.4, *) { webView.isInspectable = true }
        #endif
        return webView
    }

    private nonisolated static func waitForReady(_ events: AsyncStream<PxeEvent>) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                for await event in events where event.name == "ready" { return }
                throw PxeError("the page closed before it was ready")
            }
            group.addTask {
                try await Task.sleep(for: readyTimeout)
                throw PxeError("timed out")
            }
            try await group.next()
            group.cancelAll()
        }
    }
}

/// Holds the web view for the channel's send closure, which is created before the web view.
@MainActor
private final class WebViewBox {
    weak var webView: WKWebView?

    func deliver(_ json: String) {
        let literal = (try? String(decoding: JSONEncoder().encode(json), as: UTF8.self)) ?? "\"\""
        webView?.evaluateJavaScript("azwallHost.receive(\(literal))", completionHandler: nil)
    }
}

/// `webkit.messageHandlers.azwall.postMessage(json)` in the page. Only the PXE page itself is heard: the bridge
/// carries the store key and signing keys, so a page that replaced it (another server on the port) gets nothing.
@MainActor
final class MessageHandler: NSObject, WKScriptMessageHandler {
    private let channel: PxeChannel
    private let origin: String

    init(channel: PxeChannel, origin: String) {
        self.channel = channel
        self.origin = origin
    }

    /// The main frame of the PXE page's origin (WebKit's, not the page's word).
    static func accepts(mainFrame: Bool, protocol scheme: String, host: String, port: Int, origin: String) -> Bool {
        mainFrame && "\(scheme)://\(host):\(port)" == origin
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        let o = message.frameInfo.securityOrigin
        guard Self.accepts(mainFrame: message.frameInfo.isMainFrame, protocol: o.protocol, host: o.host, port: o.port, origin: origin),
              let json = message.body as? String else { return }
        let channel = channel
        Task { await channel.receive(json) }
    }
}

/// The PXE host's `log` events in the system log (subsystem dev.azwall, category pxe), like logcat's AzwallPxe on
/// Android. Debug builds also print them, so a console attached to the app (Xcode, devicectl) shows them.
enum PxeLog {
    private static let logger = Logger(subsystem: "dev.azwall", category: "pxe")

    static func follow(_ events: AsyncStream<PxeEvent>, bundle: String) {
        Task.detached {
            for await event in events where event.name == "log" {
                write(event.data, bundle: bundle)
            }
        }
    }

    private static func write(_ data: JSONValue, bundle: String) {
        let text = "[\(bundle)] [\(data["scope"]?.stringValue ?? "host")] \(data["message"]?.stringValue ?? "\(data)")"
        #if DEBUG
        switch data["level"]?.stringValue {
        case "error": logger.error("\(text, privacy: .public)")
        case "warn": logger.warning("\(text, privacy: .public)")
        default: logger.info("\(text, privacy: .public)")
        }
        print("AzwallPxe \(text)")
        #else
        // Release builds: lines carry addresses and transaction hashes, so they stay private in the system log.
        switch data["level"]?.stringValue {
        case "error": logger.error("\(text, privacy: .private)")
        case "warn": logger.warning("\(text, privacy: .private)")
        default: break
        }
        #endif
    }
}
