import AzwallKit
import SwiftUI

/// Create a token whose minter is this account, then mint it to yourself or to anyone, privately or publicly. A token is
/// per Aztec version, like everything on chain. Fees are paid by the network's sponsor, or by the account in Fee Juice:
/// the first token created on a network also publishes the Token contract, once for everyone, and costs more.
struct TokenStudioView: View {
    let model: AppModel
    let session: WalletSession
    let onMenu: () -> Void
    @State private var name = "Test Token"
    @State private var symbol = "TST"
    @State private var decimals = 6
    @State private var minting: String?
    @State private var sharing: TokenView?

    private var ready: Bool { session.phase == .ready && session.account?.deployed != nil }

    var body: some View {
        Form {
            if session.phase == .failed {
                Text("The wallet could not load.").foregroundStyle(Palette.danger)
            }
            if session.account?.deployed == false {
                Text("Activate the account first: it becomes the token's minter.").foregroundStyle(.secondary)
            } else {
                createSection
                let mine = session.tokens.filter { t in session.created.contains { $0.lowercased() == t.info.address.lowercased() } }
                if !mine.isEmpty {
                    Section("Your tokens") {
                        ForEach(mine) { t in
                            HStack(spacing: 4) {
                                TokenRow(token: t, showsBalance: false)
                                // Borderless: in a Form, a plain button makes the whole row its tap target.
                                Button { sharing = t } label: { Image(systemName: "square.and.arrow.up").frame(width: 36, height: 36) }
                                    .buttonStyle(.borderless).accessibilityLabel("Share \(t.info.symbol)")
                                Button { minting = t.id } label: { Image(systemName: "plus.circle").frame(width: 36, height: 36) }
                                    .buttonStyle(.borderless).accessibilityLabel("Mint \(t.info.symbol)")
                            }
                            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 12))
                        }
                    }
                }
            }
        }
        // Until the PXE is open and has read the account, the screen shows what it will hold, dimmed and inactive,
        // with what is loading in the status line, as on the home screen.
        .disabled(!ready)
        .opacity(ready ? 1 : 0.5)
        .safeAreaInset(edge: .bottom) { StatusLine(session: session).background(.bar) }
        .navigationTitle("Create a token")
        .menuButton(onMenu)
        .navigationDestination(item: $minting) { address in
            if let token = session.tokens.first(where: { $0.id == address }) { MintView(model: model, token: token, network: session.network.label, fee: Fees.line(session.check, feeJuice: session.feeJuice)) }
        }
        .sheet(item: $sharing) { ShareTokenSheet(token: $0, network: session.network) }
    }

    private var createSection: some View {
        Section {
            TextField("Name", text: $name)
            TextField("Symbol", text: $symbol).textInputAutocapitalization(.characters)
            Stepper("Decimals: \(decimals)", value: $decimals, in: 0...18)
            Button("Create") {
                let name = name.trimmingCharacters(in: .whitespaces), symbol = symbol.trimmingCharacters(in: .whitespaces), decimals = decimals
                Task { await model.sign(done: "Token creation sent.") { try await $0.deployTestToken(name: name, symbol: symbol, decimals: decimals) } }
            }
            .disabled(model.busy || name.trimmingCharacters(in: .whitespaces).isEmpty || symbol.trimmingCharacters(in: .whitespaces).isEmpty)
        } footer: {
            Text("This account becomes the minter. The token exists on this network only; creating it takes one proof.\n"
                + "Fee: \(Fees.line(session.check, feeJuice: session.feeJuice))")
        }
    }
}
