import AzwallKit
import SwiftUI

/// The wallet: network pill and account selector, private and public balances, actions, tokens and activity.
struct HomeView: View {
    let model: AppModel
    let session: WalletSession
    @State private var tab = 0
    @State private var route: Route?
    @State private var sheet: Sheet?

    enum Route: Hashable { case send, shield, receive, settings }
    enum Sheet: Identifiable { case networks, accounts, addToken; var id: Self { self } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    accountHeader
                    BalanceHero(session: session, onActivate: activate, onAddToken: { sheet = .addToken }, onShield: { route = .shield }, onRetry: refresh)
                        .padding(.top, 20)
                    actions.padding(.vertical, 20)
                    Picker("", selection: $tab) { Text("Tokens").tag(0); Text("Activity").tag(1) }.pickerStyle(.segmented).padding(.horizontal)
                    if tab == 0 { TokenList(tokens: session.tokens, onAdd: { sheet = .addToken }) } else { ActivityList(items: session.activity) }
                }
            }
            .refreshable { await session.refresh() }
            .background(Palette.background)
            .toolbar { toolbar }
            .navigationDestination(item: $route) { destination($0) }
            .sheet(item: $sheet) { sheetView($0).presentationDetents([.medium, .large]) }
            .task { await autoRefresh() }
        }
        .overlay { if let item = session.proving { ProvingView(item: item, since: session.provingSince ?? Date()) } }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button { sheet = .networks } label: {
                HStack(spacing: 6) {
                    Circle().fill(nodeHealthy ? Palette.success : Palette.pending).frame(width: 8, height: 8)
                    Text(session.network.label).font(.subheadline.weight(.semibold))
                    Image(systemName: "chevron.down").font(.caption2)
                }
                .padding(.horizontal, 12).padding(.vertical, 6).background(Capsule().fill(Palette.chip))
            }
            .foregroundStyle(.primary)
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { route = .settings } label: { Image(systemName: "gearshape") }
        }
    }

    private var nodeHealthy: Bool { model.nodes[session.network.id]?.compatible ?? (session.phase == .ready) }

    private var accountHeader: some View {
        VStack(spacing: 6) {
            Button { sheet = .accounts } label: {
                HStack(spacing: 8) {
                    AccountAvatar(address: session.account?.address ?? "0x0", size: 28)
                    Text(session.account?.alias ?? "…").font(.headline)
                    Image(systemName: "chevron.down").font(.caption)
                }
            }
            .foregroundStyle(.primary)
            if let account = session.account { CopyAddress(address: account.address) }
        }
        .padding(.top, 8)
    }

    private var actions: some View {
        let ready = session.phase == .ready && session.account?.deployed == true
        return HStack {
            CircleAction(icon: "arrow.up.right", label: "Send", enabled: ready && !session.tokens.isEmpty) { route = .send }
            CircleAction(icon: "qrcode", label: "Receive", enabled: session.account != nil) { route = .receive }
            CircleAction(icon: "shield", label: "Shield", enabled: ready && !session.tokens.isEmpty) { route = .shield }
            CircleAction(icon: "plus", label: "Token", enabled: session.phase == .ready) { sheet = .addToken }
        }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .send: TransferView(model: model, session: session, mode: .send)
        case .shield: TransferView(model: model, session: session, mode: .shield)
        case .receive: ReceiveView(session: session)
        case .settings: SettingsView(model: model, session: session)
        }
    }

    @ViewBuilder
    private func sheetView(_ sheet: Sheet) -> some View {
        switch sheet {
        case .networks: NetworksSheet(model: model, current: session.network.id) { self.sheet = nil }
        case .accounts: AccountsSheet(model: model, session: session) { self.sheet = nil }
        case .addToken: AddTokenSheet(model: model) { self.sheet = nil }
        }
    }

    private func activate() {
        Task { await model.run(done: "Account activation sent.") { try await $0.deployAccount() } }
    }

    private func refresh() {
        Task { await session.refresh() }
    }

    /// Balances are refreshed every 20 s while the screen is shown.
    private func autoRefresh() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(20))
            await session.refresh()
        }
    }
}
