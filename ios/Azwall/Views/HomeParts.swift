import AzwallKit
import SwiftUI

/// The big number: the first token's total, split into private and public like a shielded wallet does.
struct BalanceHero: View {
    let session: WalletSession
    let onActivate: () -> Void
    let onAddToken: () -> Void
    let onShield: () -> Void
    let onRetry: () -> Void

    var body: some View {
        if session.phase == .starting {
            HStack(spacing: 12) { ProgressView(); Text("Connecting to \(session.network.label)…").foregroundStyle(.secondary) }
        } else if session.phase == .failed {
            Notice(title: "Can't reach \(session.network.label)", text: friendlyError(session.error), action: "Retry", onAction: onRetry)
        } else if session.account?.deployed == false {
            Notice(icon: "paperplane", title: "Activate your account",
                   text: "Your address can already receive funds. Activating publishes your account contract; fees are sponsored on testnet.",
                   action: "Activate", onAction: onActivate)
        } else if let token = session.tokens.first {
            hero(token)
        } else {
            Notice(title: "Your account is ready",
                   text: "Add a token to see its private and public balances. On testnet, Settings can create a test token for you.",
                   action: "Add a token", onAction: onAddToken)
        }
    }

    private func hero(_ token: TokenView) -> some View {
        let d = token.info.decimals
        let priv = token.balance?.private ?? "0", pub = token.balance?.public ?? "0"
        return VStack(spacing: 8) {
            Text(token.total.map { "\(Amounts.format($0, decimals: d, maxFraction: 4)) \(token.info.symbol)" } ?? "…")
                .font(.system(size: 40, weight: .semibold)).minimumScaleFactor(0.5).lineLimit(1)
            HStack(spacing: 16) {
                Label("Private \(Amounts.format(priv, decimals: d, maxFraction: 4))", systemImage: "lock").foregroundStyle(Palette.privateFunds)
                Label("Public \(Amounts.format(pub, decimals: d, maxFraction: 4))", systemImage: "globe").foregroundStyle(Palette.publicFunds)
            }
            .font(.subheadline)
            if Amounts.isPositive(pub) {
                Button("Make \(Amounts.format(pub, decimals: d, maxFraction: 4)) \(token.info.symbol) private", action: onShield).font(.subheadline.weight(.semibold))
            }
        }
        .padding(.horizontal)
    }
}

struct Notice: View {
    var icon: String?
    let title: String
    let text: String
    let action: String
    let onAction: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            if let icon { Image(systemName: icon).foregroundStyle(Palette.accent) }
            Text(title).font(.headline)
            Text(text).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button(action, action: onAction).buttonStyle(.borderedProminent).tint(Palette.accent)
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 24).fill(Palette.card))
        .padding(.horizontal)
    }
}

struct TokenList: View {
    let tokens: [TokenView]
    let onAdd: () -> Void

    var body: some View {
        LazyVStack(spacing: 0) {
            if tokens.isEmpty {
                EmptyHint(title: "No tokens yet", text: "Add a token by its address to see its private and public balances.", action: "Add a token", onAction: onAdd)
            }
            ForEach(tokens) { TokenRow(token: $0) }
        }
    }
}

struct TokenRow: View {
    let token: TokenView

    var body: some View {
        let d = token.info.decimals
        HStack(spacing: 14) {
            TokenBadge(symbol: token.info.symbol, address: token.info.address)
            VStack(alignment: .leading) {
                Text(token.info.name).font(.subheadline.weight(.semibold))
                Text(token.info.symbol).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if let balance = token.balance, let total = token.total {
                VStack(alignment: .trailing) {
                    Text(Amounts.format(total, decimals: d, maxFraction: 4)).font(.subheadline.weight(.semibold))
                    Text("\(Amounts.format(balance.private, decimals: d, maxFraction: 2)) private · \(Amounts.format(balance.public, decimals: d, maxFraction: 2)) public")
                        .font(.caption).foregroundStyle(.secondary)
                }
            } else {
                Text("…")
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
    }
}

struct ActivityList: View {
    let items: [ActivityItem]

    var body: some View {
        LazyVStack(spacing: 0) {
            if items.isEmpty { EmptyHint(title: "No activity yet", text: "Transactions you make from this account, here or in dApps, appear here.") }
            ForEach(items) { ActivityRow(item: $0) }
        }
    }
}

struct ActivityRow: View {
    let item: ActivityItem

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(activityTitle(item)).font(.subheadline.weight(.semibold))
                Text(detail).font(.caption).foregroundStyle(.secondary)
                if item.status == .failed { Text(friendlyError(item.error)).font(.caption).foregroundStyle(Palette.danger).lineLimit(2) }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                if let amount = activityAmount(item) { Text(amount).font(.subheadline.weight(.semibold)) }
                StatusChip(status: item.status)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
    }

    private var detail: String {
        let parts: [String?] = [
            item.createdAt.formatted(.relative(presentation: .named)),
            item.origin.map { "via \($0.replacingOccurrences(of: "https://", with: ""))" },
            item.counterparty.map { item.kind == .receive ? "from \(shortAddress($0))" : "to \(shortAddress($0))" },
            item.blockNumber.map { "block \($0)" },
        ]
        return parts.compactMap { $0 }.joined(separator: " · ")
    }
}

struct EmptyHint: View {
    let title: String
    let text: String
    var action: String?
    var onAction: () -> Void = {}

    var body: some View {
        VStack(spacing: 6) {
            Text(title).font(.subheadline.weight(.semibold))
            Text(text).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            if let action { Button(action, action: onAction) }
        }
        .padding(32)
    }
}
