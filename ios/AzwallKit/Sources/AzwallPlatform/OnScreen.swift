#if canImport(UIKit)
import UIKit
import WebKit

/// iOS runs a web view's process at the priority of what it shows: a web view in no window gets a background process,
/// suspended within seconds, and WebKit then closes its OPFS files ("AccessHandle is closed"), so every later write of
/// the PXE stores failed (I/O error, "file is not a database"). The PXE's web view is put in the app's window, one
/// point wide and nearly transparent, so it runs like the visible page it pretends to be.
@MainActor
enum OnScreen {
    static func attach(_ webView: WKWebView) {
        webView.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
        // Not 0 and not hidden: WebKit treats those as not visible.
        webView.alpha = 0.01
        webView.isUserInteractionEnabled = false
        webView.accessibilityElementsHidden = true
        place(webView, attempt: 0)
    }

    static func detach(_ webView: WKWebView) {
        webView.removeFromSuperview()
    }

    /// The window may not exist yet when the wallet opens at launch: try again for a few seconds.
    private static func place(_ webView: WKWebView, attempt: Int) {
        if let window = appWindow() {
            window.addSubview(webView)
            return
        }
        guard attempt < 50 else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(200))
            place(webView, attempt: attempt + 1)
        }
    }

    private static func appWindow() -> UIWindow? {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        return windows.first { $0.isKeyWindow } ?? windows.first
    }
}
#endif
