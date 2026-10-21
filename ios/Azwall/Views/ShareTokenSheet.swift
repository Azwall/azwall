import AzwallKit
import SwiftUI

/// A token's QR code and link, for another wallet to follow it: a wallet only sees the tokens it follows, so whoever
/// receives one it does not know adds it first (Add a token, scan or paste).
struct ShareTokenSheet: View {
    let token: TokenView
    let network: NetworkInfo

    private var link: String { PaymentRequest(address: token.info.address, network: network.id).uri }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                HStack(spacing: 8) {
                    TokenBadge(symbol: token.info.symbol, address: token.info.address)
                    Text("\(token.info.name) (\(token.info.symbol))").font(.headline)
                }
                if let qr = ReceiveView.qrCode(link) {
                    Image(uiImage: qr).interpolation(.none).resizable().frame(width: 220, height: 220)
                        .padding(12).background(RoundedRectangle(cornerRadius: 16).fill(.white))
                }
                CopyAddress(address: token.info.address)
                Text("Scan it in Add a token on another wallet to see its \(token.info.symbol) balances. The token exists on \(network.label) only.")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                ShareLink("Share link", item: link).frame(maxWidth: .infinity, minHeight: 44)
            }
            .padding(24)
            .navigationTitle("Share \(token.info.symbol)")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
