import AzwallKit
import SwiftUI

/// A dApp request, docked under the page instead of over it: the page above stays fully usable (scroll it, read
/// its emojis), and nothing but the two buttons answers the request. The panel collapses to its title and buttons,
/// like an accordion, to give the page back most of the screen.
struct ApprovalPanel: View {
    let request: ApprovalRequest
    let onDecide: (Bool) -> Void
    @State private var expanded = true

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            if expanded { details }
            HStack(spacing: 12) {
                Button(rejectLabel) { onDecide(false) }.buttonStyle(.bordered).frame(maxWidth: .infinity)
                Button(approveLabel) { onDecide(true) }.buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
            }
            .controlSize(.large)
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
        .background(UnevenRoundedRectangle(topLeadingRadius: 24, topTrailingRadius: 24).fill(Palette.card).shadow(radius: 12))
        .animation(.default, value: expanded)
    }

    private var header: some View {
        Button { expanded.toggle() } label: {
            HStack {
                // The site as the browser knows it comes first: what a page says about itself is only a detail below.
                VStack(alignment: .leading, spacing: 2) {
                    Text(siteOf(request.origin)).font(.headline).lineLimit(2)
                    Text(request.title).font(.subheadline).lineLimit(expanded ? 3 : 1)
                }
                Spacer()
                Image(systemName: expanded ? "chevron.down" : "chevron.up").accessibilityLabel(expanded ? "Collapse" : "Expand")
            }
        }
        .foregroundStyle(.primary)
    }

    private var details: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if let emojis = request.emojis { emojiBox(emojis) }
                ForEach(request.details, id: \.self) { Text("• \($0)").font(.subheadline) }
                if request.kind == .transaction {
                    Text("The proof is generated on this phone; the fee is sponsored on testnet.").font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxHeight: 280)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func emojiBox(_ emojis: String) -> some View {
        VStack(spacing: 4) {
            Text(emojis).font(.system(size: 26)).multilineTextAlignment(.center).frame(maxWidth: .infinity)
            Text("The page above must show these same emojis. If it does not, reject: someone may be in between.")
                .font(.caption).foregroundStyle(Palette.pending).multilineTextAlignment(.center)
        }
        .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
    }

    private var approveLabel: String {
        switch request.kind {
        case .connect: "Connect"
        case .verify: "They match"
        case .capabilities: "Allow"
        case .transaction: "Approve and prove"
        case .authwit: "Authorize"
        }
    }

    private var rejectLabel: String { request.kind == .verify ? "They differ" : "Reject" }
}
