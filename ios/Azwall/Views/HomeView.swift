import AzwallKit
import SwiftUI

/// The wallet: network pill and account selector, private and public balances, actions, tokens and activity.
struct HomeView: View {
    let model: AppModel
    let session: WalletSession
    /// Set by the side menu too (Settings).
    @Binding var route: Route?
    let onMenu: () -> Void
    @State private var tab = 0
    @State private var sheet: Sheet?
    @State private var shared: TokenView?
    /// By id: the sheet follows the transaction while it moves from proving to a block.
    @State private var opened: String?
    /// Activating pays a fee where there is no sponsor: confirmed like any transaction first.
    @State private var reviewingActivation = false
    /// Claiming bridged Fee Juice, confirmed like any transaction first.
    @State private var reviewingClaim = false
    @State private var enteringSecret = false
    @State private var claimSecret = ""

    enum Route: Hashable { case send, shield, receive, settings, tokens }
    enum Sheet: Identifiable { case networks, accounts, addToken; var id: Self { self } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    if session.chainReset {
                        // The network was redeployed under the same name: what the wallet knew of the old chain is set aside.
                        Notice(icon: "info.circle", title: "\(session.network.label) was reset",
                               text: "It runs a new chain: the tokens, balances and history of the previous one were set aside. Your accounts are the same; activate them again to send transactions.",
                               action: "OK", onAction: session.acknowledgeChainReset)
                            .padding(.horizontal, 16)
                    }
                    accountHeader
                    BalanceHero(session: session, onActivate: { reviewingActivation = true }, onShield: { route = .shield }, onRetry: refresh,
                                onClaim: { reviewingClaim = true }, onEnterSecret: { claimSecret = ""; enteringSecret = true })
                        .padding(.top, 20)
                    actions.padding(.vertical, 20)
                    FoundTokensView(session: session)
                    Picker("", selection: $tab) { Text("Tokens").tag(0); Text("Activity").tag(1) }.pickerStyle(.segmented).padding(.horizontal)
                    if tab == 0 { TokenList(feeJuice: session.feeJuiceToken, tokens: session.tokens, stale: session.stale, onAdd: { sheet = .addToken }, onShare: { shared = $0 }) } else { ActivityList(items: session.activity) { opened = $0.id } }
                }
            }
            .refreshable { await session.refresh() }
            .background(Palette.background)
            .toolbar { toolbar }
            .navigationDestination(item: $route) { destination($0) }
            .sheet(item: $sheet) { sheetView($0).presentationDetents([.medium, .large]) }
            .sheet(isPresented: $reviewingActivation) {
                let fee = session.claimable.map(Fees.claimLine) ?? Fees.line(session.check, feeJuice: session.feeJuice)
                ReviewSheet(review: Review.activation(network: session.network.label, fee: fee), onConfirm: activate)
            }
            .sheet(isPresented: $reviewingClaim) {
                if let claim = session.claimable {
                    let amount = "\(Amounts.display(claim.amount, decimals: FeeJuice.info.decimals, maxFraction: 4)) FJ"
                    ReviewSheet(review: Review.claimFeeJuice(amount: amount, network: session.network.label)) { self.claim(claim) }
                }
            }
            .alert("Claim secret", isPresented: $enteringSecret) {
                TextField("0x… or a number", text: $claimSecret).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Cancel", role: .cancel) {}
                Button("Use it") { useSecret(claimSecret) }
            } message: {
                Text("The secret whose hash you gave the Fee Juice portal when bridging, in hex (0x…) or as a number. It only lets someone credit your account, never take the deposit.")
            }
            .sheet(item: $shared) { token in
                ShareTokenSheet(token: token, network: session.network) { session.removeToken(token.info.address) }.presentationDetents([.large])
            }
            .sheet(isPresented: Binding(get: { session.activity.contains { $0.id == opened } }, set: { if !$0 { opened = nil } })) {
                if let item = session.activity.first(where: { $0.id == opened }) { ActivityDetailsSheet(item: item) }
            }
            .task { await autoRefresh() }
            .safeAreaInset(edge: .bottom) { StatusLine(session: session).background(.bar) }
            .animation(.default, value: session.task)
        }
        .overlay { if let item = session.proving { ProvingView(item: item, since: session.provingSince ?? Date(), onCancel: session.cancelProving) } }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button(action: onMenu) { Image(systemName: "line.3.horizontal") }.accessibilityLabel("Menu")
        }
        ToolbarItem(placement: .principal) {
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
            Button { route = .receive } label: { Image(systemName: "qrcode") }.accessibilityLabel("Receive")
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
        case .settings: SettingsView(model: model, session: session, onMenu: onMenu)
        case .tokens: TokenStudioView(model: model, session: session, onMenu: onMenu)
        }
    }

    @ViewBuilder
    private func sheetView(_ sheet: Sheet) -> some View {
        switch sheet {
        case .networks: NetworksSheet(model: model, current: session.network.id) { self.sheet = nil }
        case .accounts: AccountsSheet(model: model, session: session) { self.sheet = nil }
        case .addToken: AddTokenSheet(model: model, network: session.network.id) { self.sheet = nil }
        }
    }

    private func activate() {
        Task { await model.sign(done: "Account activation sent.") { try await $0.deployAccount() } }
    }

    private func claim(_ deposit: FeeJuiceDeposit) {
        Task { await model.sign(done: "Fee Juice claim sent.") { try await $0.claimFeeJuice(deposit) } }
    }

    private func useSecret(_ text: String) {
        Task {
            do { try await session.addClaimSecret(text) } catch { model.message = error.localizedDescription }
        }
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
