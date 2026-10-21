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
                        ForEach(mine) { t in CreatedTokenRow(model: model, token: t, network: session.network) { minting = t.id } }
                    }
                }
            }
        }
        .navigationTitle("Create a token")
        .navigationDestination(item: $minting) { address in
            if let token = session.tokens.first(where: { $0.id == address }) { MintView(model: model, token: token) }
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

/// One token this account created: its balance, its address to share, and the way to mint it.
private struct CreatedTokenRow: View {
    let model: AppModel
    let token: TokenView
    let network: NetworkInfo
    let onMint: () -> Void
    @State private var sharing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                TokenBadge(symbol: token.info.symbol, address: token.info.address)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(token.info.name) (\(token.info.symbol))").font(.subheadline.weight(.semibold))
                    Text("You hold \(token.total.map { Amounts.display($0, decimals: token.info.decimals, maxFraction: 4) } ?? "…") · \(token.info.decimals) decimals")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            CopyAddress(address: token.info.address)
            // Borderless: in a Form, a plain button makes the whole row its tap target.
            HStack {
                // A wallet only sees tokens it follows: the share sheet's QR code lets another one add this one.
                Button("Share so others can add it") { sharing = true }.buttonStyle(.borderless)
                Spacer()
                Button("Mint", action: onMint).buttonStyle(.borderedProminent).disabled(model.busy)
            }
        }
        .padding(.vertical, 4)
        .sheet(isPresented: $sharing) { ShareTokenSheet(token: token, network: network) }
    }
}
