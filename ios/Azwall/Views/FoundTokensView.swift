import AzwallKit
import SwiftUI

/// Tokens the account holds that the wallet does not follow yet, found among the contracts dApps registered: one
/// tap adds each to the token list, or dismisses it for good.
struct FoundTokensView: View {
    let session: WalletSession

    var body: some View {
        if !session.found.isEmpty {
            Card {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Found in your apps").font(.subheadline.weight(.semibold))
                    Text("Tokens you hold from dApps that the wallet does not show yet.").font(.caption).foregroundStyle(.secondary)
                    ForEach(session.found) { row($0) }
                }
            }
            .padding(.horizontal).padding(.vertical, 8)
        }
    }

    private func row(_ token: FoundToken) -> some View {
        HStack(spacing: 12) {
            TokenBadge(symbol: token.symbol, address: token.address)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(token.name) (\(token.symbol))").font(.subheadline.weight(.semibold)).lineLimit(1)
                Text("\(Amounts.display(token.private, decimals: token.decimals, maxFraction: 2)) private · \(Amounts.display(token.public, decimals: token.decimals, maxFraction: 2)) public")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button("Add") { session.followFound(token) }.buttonStyle(.bordered)
            Button { session.dismissFound(token) } label: { Image(systemName: "xmark") }.foregroundStyle(.secondary).accessibilityLabel("Not interested")
        }
    }
}

/// A small line at the bottom right, like an IDE's status bar: what runs in the background (PXE sync, token search,
/// transactions waiting for a block). Informative only: it never blocks the screen.
struct StatusLine: View {
    let session: WalletSession

    var body: some View {
        if let text = StatusText.text(phase: session.phase, task: session.task, activity: session.activity) {
            HStack(spacing: 8) {
                Spacer()
                ProgressView().controlSize(.mini)
                Text(text).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
            .transition(.opacity)
        }
    }
}
