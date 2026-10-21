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
    @State private var managing: String?

    var body: some View {
        Form {
            // The account shown comes from the last session until the PXE is open: nothing can be sent before.
            if session.phase != .ready {
                HStack(spacing: 12) {
                    if session.phase == .starting { ProgressView() }
                    Text(session.phase == .starting ? "Opening the wallet…" : "The wallet could not open.").foregroundStyle(.secondary)
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
                            // As on the home screen; the whole row opens the token, which only shows it.
                            Button { managing = t.id } label: {
                                HStack(spacing: 0) {
                                    TokenRow(token: t)
                                    Text("Manage").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.accent)
                                }
                            }
                            .buttonStyle(.plain)
                            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 16))
                        }
                    }
                }
            }
        }
        .navigationTitle("Create a token")
        .navigationDestination(item: $managing) { address in
            if let token = session.tokens.first(where: { $0.id == address }) { TokenManageView(model: model, token: token, network: session.network) }
        }
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
