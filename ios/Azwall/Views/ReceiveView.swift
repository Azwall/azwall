import AzwallKit
import CoreImage.CIFilterBuiltins
import SwiftUI

struct ReceiveView: View {
    let session: WalletSession

    var body: some View {
        if let account = session.account {
            VStack(spacing: 16) {
                HStack(spacing: 8) { AccountAvatar(address: account.address, size: 28); Text(account.alias).font(.headline) }
                if let qr = Self.qrCode(account.address) {
                    Image(uiImage: qr).interpolation(.none).resizable().frame(width: 240, height: 240)
                        .padding(12).background(RoundedRectangle(cornerRadius: 16).fill(.white))
                }
                Text(account.address).font(.mono).multilineTextAlignment(.center).textSelection(.enabled)
                Text("Only send tokens on \(session.network.label) to this address. Funds sent privately stay hidden from everyone but you.")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                // Balances are read per followed token: an unknown one goes unseen, privately or publicly.
                Text("Your wallet only shows the tokens in your list: if you expect a token you do not have yet, add it first (Token, then scan its QR code), or you will not see what you receive.")
                    .font(.footnote).foregroundStyle(Palette.pending).multilineTextAlignment(.center)
                Spacer()
                Button("Copy address") { UIPasteboard.general.string = account.address }.buttonStyle(PrimaryButtonStyle())
                ShareLink("Share", item: account.address).frame(maxWidth: .infinity, minHeight: 44)
            }
            .padding(24)
            .background(Palette.background)
            .navigationTitle("Receive")
        }
    }

    /// QR code of `text`, one pixel per module (drawn without smoothing).
    static func qrCode(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let image = filter.outputImage, let cg = CIContext().createCGImage(image, from: image.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Full screen while a transaction is proven on the device: the wait is the price of privacy, so the app
/// says what is happening and how long it usually takes.
struct ProvingView: View {
    let item: ActivityItem
    let since: Date
    let onCancel: () -> Void
    @State private var spin = false
    @State private var cancelling = false

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            VStack(spacing: 16) {
                ZStack {
                    Circle().stroke(Palette.privateFunds.opacity(0.15), lineWidth: 6)
                    Circle().trim(from: 0, to: 0.28).stroke(Palette.privateFunds, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                        .rotationEffect(.degrees(spin ? 360 : 0))
                        .animation(.linear(duration: 1.4).repeatForever(autoreverses: false), value: spin)
                    Image("Logo").resizable().scaledToFit().frame(width: 52, height: 52)
                }
                .frame(width: 96, height: 96)
                // Read on every tick: once the proof is handed over, the transaction may reach the network at any
                // moment, and no cancel promises otherwise.
                TimelineView(.periodic(from: since, by: 0.5)) { context in
                    let sending = ProofGate.shared.released
                    VStack(spacing: 16) {
                        Text(sending ? "Sending to the network…" : "\(provingTitle(item.kind))…").font(.title2.bold())
                        Text(sending
                            ? "The proof is ready and the transaction is on its way. It can no longer be cancelled; the activity follows it."
                            : "Generating a zero-knowledge proof on this phone. Nothing leaves the device until the proof is ready.")
                            .multilineTextAlignment(.center).foregroundStyle(.secondary)
                        Text("\(Int(context.date.timeIntervalSince(since))) s · usually 15 to 30 s").font(.headline).foregroundStyle(Palette.privateFunds)
                        // A proof cannot be stopped midway: it is dropped once ready, and nothing is sent. Once released, it is too late.
                        if !sending {
                            Button(cancelling ? "Cancelling, nothing will be sent…" : "Cancel") { cancelling = true; onCancel() }
                                .buttonStyle(.bordered).disabled(cancelling)
                        }
                    }
                }
            }
            .padding(32)
        }
        .onAppear { spin = true }
    }
}
