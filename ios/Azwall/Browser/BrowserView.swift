import AzwallKit
import SwiftUI
import WebKit

/// The Explore tab: any website, with dApps able to connect to the wallet.
struct BrowserView: View {
    let browser: BrowserController
    let sessions: [DappSession]
    let onMenu: () -> Void
    let onConnections: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            AddressBar(url: browser.url, connections: sessions.count, onMenu: onMenu, onOpen: browser.open, onConnections: onConnections)
            if browser.progress < 1 { ProgressView(value: browser.progress).progressViewStyle(.linear).frame(height: 2) }
            WebViewHost(webView: browser.webView)
            navigationRow
        }
        .onAppear { if browser.webView.url == nil { browser.open(BrowserController.home.absoluteString) } }
    }

    private var navigationRow: some View {
        HStack(spacing: 20) {
            Button(action: browser.back) { Image(systemName: "chevron.backward") }.disabled(!browser.canGoBack)
            Button(action: browser.forward) { Image(systemName: "chevron.forward") }.disabled(!browser.canGoForward)
            Button { browser.open(BrowserController.home.absoluteString) } label: { Image(systemName: "house") }
            Button(action: browser.reload) { Image(systemName: "arrow.clockwise") }
            Text(browser.title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Spacer()
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }
}

private struct AddressBar: View {
    let url: String
    let connections: Int
    let onMenu: () -> Void
    let onOpen: (String) -> Void
    let onConnections: () -> Void
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onMenu) { Image(systemName: "line.3.horizontal") }.accessibilityLabel("Menu")
            HStack(spacing: 6) {
                if url.hasPrefix("https://") { Image(systemName: "lock.fill").font(.caption2).foregroundStyle(.secondary) }
                TextField("Search or enter an address", text: $text)
                    .focused($focused).keyboardType(.webSearch).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .submitLabel(.go)
                    .onSubmit { onOpen(text); focused = false }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(Capsule().fill(Palette.chip))
            Button(action: onConnections) {
                Image(systemName: "link")
                    .overlay(alignment: .topTrailing) {
                        if connections > 0 {
                            Text("\(connections)").font(.caption2.bold()).foregroundStyle(.white).padding(3)
                                .background(Circle().fill(Palette.success)).offset(x: 8, y: -8)
                        }
                    }
            }
            .accessibilityLabel("Connected sites")
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .onChange(of: url) { if !focused { text = url } }
        .onChange(of: focused) {
            // Like a browser: focusing the bar selects the address, so typing replaces it.
            if focused { DispatchQueue.main.async { UIApplication.shared.sendAction(#selector(UIResponder.selectAll(_:)), to: nil, from: nil, for: nil) } }
        }
    }
}

/// The browser's web view, kept by the controller across tab switches.
private struct WebViewHost: UIViewRepresentable {
    let webView: WKWebView

    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ view: WKWebView, context: Context) {}
}

/// The sites connected to the wallet, each with its emojis, and a way to disconnect them.
struct ConnectionsSheet: View {
    let sessions: [DappSession]
    let networkLabel: String
    let onDisconnect: (String) -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if sessions.isEmpty { Text("No site is connected.").foregroundStyle(.secondary) }
                    ForEach(sessions) { s in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.origin.replacingOccurrences(of: "https://", with: "")).font(.subheadline.weight(.semibold))
                                Text("\(s.emojis) · \(s.granted ? "access granted" : "connecting")").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Disconnect") { onDisconnect(s.sessionId) }.buttonStyle(.borderless)
                        }
                    }
                } footer: {
                    Text("Sites connect through the wallet-sdk protocol, on \(networkLabel). Every transaction still asks you first.")
                }
            }
            .navigationTitle("Connected sites")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
