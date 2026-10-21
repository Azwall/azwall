import AzwallKit
import SwiftUI

/// Test networks: create a token whose minter is this account, then mint it to yourself or to anyone, privately or
/// publicly. A token is per Aztec version, like everything on chain; fees are paid by the testnet's sponsor.
struct TokenStudioView: View {
    let model: AppModel
    let session: WalletSession
    @State private var name = "Test Token"
    @State private var symbol = "TST"
    @State private var decimals = 6
    @State private var minting: String?
    @State private var sharing: TokenView?

    var body: some View {
        Form {
            // The account shown comes from the last session until the PXE is open: nothing can be sent before.
            if session.phase != .ready {
                HStack(spacing: 12) {
                    if session.phase == .starting { ProgressView() }
                    Text(session.phase == .starting ? "Loading the wallet…" : "The wallet could not load.").foregroundStyle(.secondary)
                }
            } else if session.account?.deployed == nil {
                // Unknown until the PXE has read it: the account is loading, not inactive.
                HStack(spacing: 12) { ProgressView(); Text("Loading the account…").foregroundStyle(.secondary) }
            } else if session.account?.deployed == false {
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
        .navigationTitle("Create a token")
        .navigationDestination(item: $minting) { address in
            if let token = session.tokens.first(where: { $0.id == address }) { MintView(model: model, token: token) }
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
                Task { await model.run(done: "Token creation sent.") { try await $0.deployTestToken(name: name, symbol: symbol, decimals: decimals) } }
            }
            .disabled(model.busy || name.trimmingCharacters(in: .whitespaces).isEmpty || symbol.trimmingCharacters(in: .whitespaces).isEmpty)
        } footer: {
            Text("This account becomes the minter. The token exists on this network only; creating it takes one proof.")
        }
    }
}
