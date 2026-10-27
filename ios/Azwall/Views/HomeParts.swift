import AzwallKit
import SwiftUI

/// The big number: the hero token's total (WalletSession.hero), split into private and public like a shielded wallet does.
struct BalanceHero: View {
    let session: WalletSession
    let onActivate: () -> Void
    let onShield: () -> Void
    let onRetry: () -> Void
    /// Claims Fee Juice bridged to the active account (WalletSession.claimable).
    var onClaim: () -> Void = {}
    /// Asks for the claim secret of a deposit made with one of the user's own.
    var onEnterSecret: () -> Void = {}

    var body: some View {
        // While the PXE starts, the hero already shows (Fee Juice at 0, or last session's balances): the status
        // line says what loads.
        if session.phase == .failed {
            Notice(title: "Can't reach \(session.network.label)", text: friendlyError(session.error), action: "Retry", onAction: onRetry)
        } else if session.account?.deployed == false && session.activating {
            Notice(icon: "paperplane", title: "Activating your account",
                   text: "Activation sent: your account can send transactions once it is in a block.", action: nil, onAction: {})
        } else if session.account?.deployed == false {
            // Without a sponsor, a claim or Fee Juice, an activation would only fail once proven: not offered.
            let canPay = Fees.canActivate(session.check, feeJuice: session.feeJuice, claim: session.claimable)
            Notice(icon: "paperplane", title: "Activate your account",
                   text: Fees.activation(session.check, feeJuice: session.feeJuice, claim: session.claimable, arriving: session.arriving),
                   action: canPay ? "Activate" : nil, onAction: onActivate)
        } else {
            hero(session.hero).stalePulse(session.stale)
        }
    }

    private func hero(_ token: TokenView) -> some View {
        let d = token.info.decimals
        return VStack(spacing: 8) {
            heroAmount(token.total.map { "\(Amounts.display($0, decimals: d, maxFraction: 4)) \(token.info.symbol)" } ?? "…")
            if FeeJuice.isIt(token) {
                Text(feeJuiceRole).font(.subheadline).foregroundStyle(.secondary)
            } else {
                split(token)
            }
            if let bridged = session.bridged { bridgedLine(bridged) }
        }
        .padding(.horizontal)
    }

    /// Bridged Fee Juice: a button when it can be claimed now or needs its secret, else a line.
    @ViewBuilder
    private func bridgedLine(_ bridged: Bridged) -> some View {
        if bridged.claim {
            Button("Claim \(bridged.text)", action: onClaim).font(.subheadline.weight(.semibold))
        } else if bridged.askSecret {
            Button("\(bridged.text): enter it", action: onEnterSecret).font(.subheadline.weight(.semibold))
        } else {
            Text(bridged.text).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func split(_ token: TokenView) -> some View {
        let d = token.info.decimals
        let priv = token.balance?.private ?? "0", pub = token.balance?.public ?? "0"
        VStack(spacing: 8) {
            HStack(spacing: 16) {
                Label("Private \(Amounts.display(priv, decimals: d, maxFraction: 4))", systemImage: "lock").foregroundStyle(Palette.privateFunds)
                Label("Public \(Amounts.display(pub, decimals: d, maxFraction: 4))", systemImage: "globe").foregroundStyle(Palette.publicFunds)
            }
            .font(.subheadline)
            if Amounts.isPositive(pub) {
                Button("Make \(Amounts.display(pub, decimals: d, maxFraction: 4)) \(token.info.symbol) private", action: onShield).font(.subheadline.weight(.semibold))
            }
        }
    }

    /// With several tokens, tapping the amount picks which one the top of the screen shows.
    @ViewBuilder
    private func heroAmount(_ text: String) -> some View {
        let label = Text(text).font(.system(size: 40, weight: .semibold)).minimumScaleFactor(0.5).lineLimit(1)
        if session.shown.count > 1 {
            Menu {
                ForEach(session.shown) { t in Button("\(t.info.name) (\(t.info.symbol))") { session.choosePrimary(t.info.address) } }
            } label: {
                HStack(spacing: 6) { label; Image(systemName: "chevron.down").font(.title3) }
            }
            .foregroundStyle(.primary)
            .accessibilityHint("Choose the token shown here")
        } else {
            label
        }
    }
}

struct Notice: View {
    var icon: String?
    let title: String
    let text: String
    let action: String?
    let onAction: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            if let icon { Image(systemName: icon).foregroundStyle(Palette.accent) }
            Text(title).font(.headline)
            Text(text).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            if let action { Button(action, action: onAction).buttonStyle(.borderedProminent).tint(Palette.accent) }
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 24).fill(Palette.card))
        .padding(.horizontal)
    }
}

/// What Fee Juice is for, under its balance.
let feeJuiceRole = "Pays your transaction fees · public only"

struct TokenList: View {
    /// Shown first, and not a button: Fee Juice cannot be sent or shared.
    var feeJuice: TokenView?
    let tokens: [TokenView]
    var stale = false
    let onAdd: () -> Void
    var onShare: (TokenView) -> Void = { _ in }

    var body: some View {
        LazyVStack(spacing: 0) {
            if let feeJuice { TokenRow(token: feeJuice, stale: stale) }
            if tokens.isEmpty {
                EmptyHint(title: "No tokens yet", text: "Add a token by its address to see its private and public balances.", action: "Add a token", onAction: onAdd)
            }
            ForEach(tokens) { token in
                Button { onShare(token) } label: { TokenRow(token: token, stale: stale) }.buttonStyle(.plain).accessibilityHint("Share")
            }
        }
    }
}

struct TokenRow: View {
    let token: TokenView
    var stale = false
    /// Off in the token studio, where the row only names the token.
    var showsBalance = true

    var body: some View {
        let d = token.info.decimals
        HStack(spacing: 14) {
            TokenBadge(symbol: token.info.symbol, address: token.info.address)
            VStack(alignment: .leading) {
                Text(token.info.name).font(.subheadline.weight(.semibold))
                Text(token.info.symbol).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if !showsBalance {
                EmptyView()
            } else if let balance = token.balance, let total = token.total {
                VStack(alignment: .trailing) {
                    Text(Amounts.display(total, decimals: d, maxFraction: 4)).font(.subheadline.weight(.semibold))
                    Text(FeeJuice.isIt(token) ? "Pays your fees" : "\(Amounts.display(balance.private, decimals: d, maxFraction: 2)) private · \(Amounts.display(balance.public, decimals: d, maxFraction: 2)) public")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .stalePulse(stale)
            } else {
                Text("…")
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
    }
}

struct ActivityList: View {
    let items: [ActivityItem]
    var onOpen: (ActivityItem) -> Void = { _ in }

    var body: some View {
        LazyVStack(spacing: 0) {
            if items.isEmpty { EmptyHint(title: "No activity yet", text: "Transactions you make from this account, here or in dApps, appear here.") }
            ForEach(items) { item in
                Button { onOpen(item) } label: { ActivityRow(item: item).contentShape(Rectangle()) }.buttonStyle(.plain).accessibilityHint("Details")
            }
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
            item.origin.map { "via \(siteOf($0))" },
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
