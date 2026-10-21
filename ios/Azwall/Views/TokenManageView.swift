import AzwallKit
import SwiftUI

/// One token this account created: what it holds of it, the QR code for others to add it, and minting.
struct TokenManageView: View {
    let model: AppModel
    let token: TokenView
    let network: NetworkInfo
    @State private var minting = false

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                holdings
                TokenShareCard(token: token, network: network)
            }
            .padding(20)
        }
        .background(Palette.background)
        .navigationTitle(token.info.name)
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            Button("Mint \(token.info.symbol)") { minting = true }.buttonStyle(PrimaryButtonStyle()).disabled(model.busy).padding()
        }
        .navigationDestination(isPresented: $minting) { MintView(model: model, token: token) }
    }

    private var holdings: some View {
        let d = token.info.decimals
        return VStack(alignment: .leading, spacing: 8) {
            line("Private", token.balance.map { Amounts.display($0.private, decimals: d, maxFraction: 4) } ?? "…")
            line("Public", token.balance.map { Amounts.display($0.public, decimals: d, maxFraction: 4) } ?? "…")
            line("Decimals", "\(d)")
            Text("You are its minter: only this account can create more.").font(.footnote).foregroundStyle(.secondary)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Palette.card))
    }

    private func line(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.subheadline.weight(.semibold))
        }
    }
}
