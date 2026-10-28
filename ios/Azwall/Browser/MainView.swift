import AzwallKit
import SwiftUI

enum MainTab { case wallet, explore }

/// The unlocked app: the wallet or the browser, behind a side menu as in MetaMask mobile, with dApp requests docked
/// under whichever is shown.
struct MainView: View {
    let model: AppModel
    let session: WalletSession
    @State private var tab = MainTab.wallet
    @State private var menuOpen = false
    @State private var route: HomeView.Route?
    @State private var showConnections = false
    @State private var browser: BrowserTabs

    init(model: AppModel, session: WalletSession) {
        self.model = model
        self.session = session
        _browser = State(initialValue: BrowserTabs(approvals: model.approvals))
    }

    var body: some View {
        ZStack(alignment: .leading) {
            VStack(spacing: 0) {
                switch tab {
                case .wallet:
                    HomeView(model: model, session: session, route: $route, onMenu: openMenu) { url in
                        browser.open(page: url)
                        tab = .explore
                    }
                case .explore:
                    BrowserView(browser: browser, network: session.network, sessions: session.dapps.sessions, onMenu: openMenu, onConnections: { showConnections = true })
                }
                if let request = model.approvals.pending.first {
                    ApprovalPanel(request: request) { approved in
                        Task { await model.approvals.answer(request, approved: approved, owner: model.confirmOwner, settle: session.settle) }
                    }
                        .id(request.id)
                        .transition(.move(edge: .bottom))
                }
            }
            .animation(.default, value: model.approvals.pending.first?.id)
            SideMenu(open: $menuOpen, session: session, tab: tab, onSelect: select)
        }
        .sheet(isPresented: $showConnections) {
            ConnectionsSheet(sessions: session.dapps.sessions, networkLabel: session.network.label) { id in
                Task { await model.run { try await $0.dapps.disconnect(id) } }
            }
            .presentationDetents([.medium])
        }
        // A request comes from a page: bring the browser and that page's tab forward, visible above the panel.
        .onChange(of: model.approvals.pending.first?.id) {
            guard let request = model.approvals.pending.first else { return }
            browser.selectPage(request.tab)
            tab = .explore
        }
        .task(id: session.network.id) { attachBrowser() }
    }

    private func openMenu() {
        withAnimation { menuOpen = true }
    }

    private func select(_ item: SideMenu.Item) {
        withAnimation { menuOpen = false }
        switch item {
        case .wallet: tab = .wallet; route = nil
        case .explore: tab = .explore
        case .settings: tab = .wallet; route = .settings
        case .tokens: tab = .wallet; route = .tokens
        case .lock: Task { await model.lock() }
        }
    }

    /// The selected network's content script, talking to its PXE host's gateway.
    private func attachBrowser() {
        let file = Bundle.main.resourceURL?.appendingPathComponent("pxe/\(session.network.pxeBundle)/dapp-content-script.js")
        guard let file else { return }
        do {
            browser.attach(session.dapps, contentScript: try String(contentsOf: file, encoding: .utf8))
        } catch {
            // A build without the network's content script: the browser opens sites but no dApp can reach the wallet.
            DiagnosticLog.shared.report("browser", error)
            model.message = Browsing.noConnectionScript(network: session.network.label)
        }
    }
}

/// The side menu behind the top-left button: who and where you are, then where to go.
struct SideMenu: View {
    enum Item { case wallet, explore, tokens, settings, lock }

    @Binding var open: Bool
    let session: WalletSession
    let tab: MainTab
    let onSelect: (Item) -> Void

    var body: some View {
        ZStack(alignment: .leading) {
            if open {
                Color.black.opacity(0.3).ignoresSafeArea().onTapGesture { withAnimation { open = false } }.transition(.opacity)
                panel.transition(.move(edge: .leading))
            }
        }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 4) {
            VStack(alignment: .leading, spacing: 8) {
                if let account = session.account { AccountAvatar(address: account.address, size: 48) }
                Text(session.account?.alias ?? "").font(.headline)
                Text(session.network.label).font(.caption).foregroundStyle(.secondary)
            }
            .padding(24)
            Divider()
            row("wallet.bifold", "Wallet", selected: tab == .wallet, .wallet)
            row("safari", "Explore", selected: tab == .explore, .explore)
            // Every network: a test token where there is no faucet for arbitrary tokens, or a real one, paid in Fee Juice.
            row("dollarsign.circle", "Create a token", selected: false, .tokens)
            row("gearshape", "Settings", selected: false, .settings)
            row("lock", "Lock wallet", selected: false, .lock)
            Spacer()
        }
        .frame(width: 300)
        .frame(maxHeight: .infinity)
        .background(Palette.card.ignoresSafeArea())
    }

    private func row(_ icon: String, _ label: String, selected: Bool, _ item: Item) -> some View {
        Button { onSelect(item) } label: {
            Label(label, systemImage: icon).font(.body.weight(selected ? .semibold : .regular))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16).padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 24).fill(selected ? Palette.chip : .clear))
        }
        .foregroundStyle(.primary)
        .padding(.horizontal, 12)
    }
}
