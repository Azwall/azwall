import AzwallKit
import SwiftUI

/// Mints one of the account's tokens, laid out like Send: to this account (recipient left empty) or to anyone.
struct MintView: View {
    let model: AppModel
    let token: TokenView
    @Environment(\.dismiss) private var dismiss
    @State private var toPrivate = true
    @State private var to = ""
    @State private var amountText = "1000"
    @State private var scanNote: String?

    private var recipientError: String? {
        to.isEmpty || PaymentRequest.isAddress(to) ? nil : "an Aztec address is 0x followed by 64 hexadecimal characters"
    }

    private var amount: Result<String, Error> { Result { try Amounts.parse(amountText, decimals: token.info.decimals) } }

    private var amountError: String? {
        guard !amountText.isEmpty, case .failure(let e) = amount else { return nil }
        return e.localizedDescription
    }

    var body: some View {
        Form {
            Picker("", selection: $toPrivate) {
                Text("Private").tag(true)
                Text("Public").tag(false)
            }
            .pickerStyle(.segmented)
            HStack(spacing: 12) {
                TokenBadge(symbol: token.info.symbol, address: token.info.address)
                Text("\(token.info.name) (\(token.info.symbol))").font(.subheadline.weight(.semibold))
            }
            RecipientSection(placeholder: "Recipient (empty: this account)", to: $to, note: recipientError ?? scanNote, onScan: apply)
                .onChange(of: to) { scanNote = nil }
            Section {
                HStack {
                    TextField("Amount", text: $amountText).keyboardType(.decimalPad)
                    Text(token.info.symbol).foregroundStyle(.secondary)
                }
            } footer: {
                if let amountError { Text(amountError).foregroundStyle(Palette.danger) }
            }
            Section {
                Text(toPrivate ? "Private: the new tokens are a note only the recipient can see." : "Public: the new balance is visible to everyone.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Mint \(token.info.symbol)")
        .safeAreaInset(edge: .bottom) {
            Button(toPrivate ? "Mint privately" : "Mint publicly", action: mint).buttonStyle(PrimaryButtonStyle())
                .disabled(model.busy || (try? amount.get()) == nil || recipientError != nil).padding()
        }
    }

    private func mint() {
        guard let units = try? amount.get() else { return }
        let info = token.info, toPrivate = toPrivate, to = to
        Task { await model.run(done: "Mint sent.") { try await $0.mint(token: info, amount: units, toPrivate: toPrivate, to: to) } }
        dismiss()
    }

    private func apply(_ text: String) {
        guard let request = PaymentRequest.parse(text) else { scanNote = "This QR code is not an Aztec address"; return }
        to = request.address
    }
}
