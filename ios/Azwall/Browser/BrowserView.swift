import AzwallKit
import SwiftUI
import WebKit

/// The Explore tab: tabs on a start page of the network's dApps, then any website, with dApps able to connect.
struct BrowserView: View {
    let browser: BrowserTabs
    let network: NetworkInfo
    let sessions: [DappSession]
    let onMenu: () -> Void
    let onConnections: () -> Void
    @AppStorage(BrowserTabs.engineKey) private var engine = SearchEngine.default.rawValue
    @State private var switching = false

    var body: some View {
        let tab = browser.current
        VStack(spacing: 0) {
            AddressBar(url: tab.start ? "" : tab.url, connections: sessions.count, onMenu: onMenu, onOpen: tab.open, onConnections: onConnections)
            if tab.start {
                StartPage(network: network, sessions: sessions, engine: SearchEngine.named(engine), onOpen: tab.open).frame(maxHeight: .infinity)
            } else {
                if tab.progress < 1 { ProgressView(value: tab.progress).progressViewStyle(.linear).frame(height: 2) }
                WebViewHost(webView: tab.webView).id(tab.id)
            }
            navigationRow(tab)
        }
        .sheet(isPresented: $switching) { TabsSheet(browser: browser) { switching = false }.presentationDetents([.medium, .large]) }
    }

    private func navigationRow(_ tab: BrowserTab) -> some View {
        HStack(spacing: 20) {
            Button(action: tab.back) { Image(systemName: "chevron.backward") }.disabled(tab.start)
            Button(action: tab.forward) { Image(systemName: "chevron.forward") }
                .disabled(!(tab.canGoForward || (tab.start && !tab.url.isEmpty)))
            Button(action: tab.home) { Image(systemName: "house") }.disabled(tab.start)
            Button(action: tab.reload) { Image(systemName: "arrow.clockwise") }.disabled(tab.start)
            Text(tab.start ? "" : tab.title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Spacer()
            // Like a mobile browser: the number of open tabs in a square, which opens the tab list.
            Button { switching = true } label: {
                Text("\(browser.tabs.count)").font(.caption2.bold()).frame(width: 22, height: 22)
                    .overlay(RoundedRectangle(cornerRadius: 5).stroke(lineWidth: 1.5))
            }
            .accessibilityLabel("Tabs")
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }
}

/// The open tabs: switch to one, close one, or open a new one on the start page.
private struct TabsSheet: View {
    let browser: BrowserTabs
    let close: () -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(browser.tabs) { tab in
                    HStack {
                        Button { browser.select(tab); close() } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(tab.start ? "Start page" : (tab.title.isEmpty ? tab.url : tab.title)).lineLimit(1)
                                if !tab.start { Text(hostOf(tab.url)).font(.caption).foregroundStyle(.secondary) }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.borderless)
                        Button { browser.close(tab) } label: { Image(systemName: "xmark") }.buttonStyle(.borderless).accessibilityLabel("Close tab")
                    }
                    .foregroundStyle(.primary)
                    .listRowBackground(tab === browser.current ? Palette.chip : nil)
                }
                Button("+ New tab") { browser.open(); close() }
            }
            .navigationTitle("Tabs")
            .navigationBarTitleDisplayMode(.inline)
        }
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
                                Text(siteOf(s.origin)).font(.subheadline.weight(.semibold))
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
