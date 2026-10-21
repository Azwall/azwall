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

    var body: some View {
        Form {
            if session.account?.deployed != true {
                Text("Activate the account first: it becomes the token's minter.").foregroundStyle(.secondary)
            } else {
                createSection
                let mine = session.tokens.filter { t in session.created.contains { $0.lowercased() == t.info.address.lowercased() } }
                if !mine.isEmpty {
                    Section("Your tokens") { ForEach(mine) { CreatedTokenRow(model: model, token: $0) } }
                }
            }
        }
        .navigationTitle("Create a token")
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

/// One token this account created: its balance, its address to share, and minting to anyone.
private struct CreatedTokenRow: View {
    let model: AppModel
    let token: TokenView
    @State private var amount = "1000"
    @State private var toPrivate = true
    @State private var to = ""

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
            HStack {
                TextField("Amount", text: $amount).keyboardType(.decimalPad)
                Toggle(toPrivate ? "Private" : "Public", isOn: $toPrivate).fixedSize()
            }
            TextField("To (empty: this account)", text: $to).font(.mono).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Mint", action: mint).disabled(model.busy || amount.isEmpty)
        }
        .padding(.vertical, 4)
    }

    private func mint() {
        let info = token.info, amount = amount, toPrivate = toPrivate, to = to
        Task {
            await model.run(done: "Mint sent.") { session in
                try await session.mint(token: info, amount: try Amounts.parse(amount, decimals: info.decimals), toPrivate: toPrivate, to: to)
            }
        }
    }
}
