import AzwallKit
import SwiftUI

/// Send moves funds to someone else; Shield moves the user's own funds between public and private.
struct TransferView: View {
    enum Mode { case send, shield }

    let model: AppModel
    let session: WalletSession
    let mode: Mode
    @Environment(\.dismiss) private var dismiss
    @State private var tokenIndex = 0
    @State private var publicSide = false
    @State private var to = ""
    @State private var amountText = ""
    @State private var scanNote: String?
    @State private var scanned: String?

    private var token: TokenView? { session.tokens.indices.contains(tokenIndex) ? session.tokens[tokenIndex] : nil }

    private var kind: TransferKind {
        switch mode {
        case .send: publicSide ? .public : .private
        case .shield: publicSide ? .shield : .unshield
        }
    }

    /// Private sends and unshielding spend private notes; the others spend the public balance.
    private var available: String {
        guard let balance = token?.balance else { return "0" }
        return kind == .private || kind == .unshield ? balance.private : balance.public
    }

    private var amount: Result<String, Error> { Result { try Amounts.parse(amountText, decimals: token?.info.decimals ?? 0) } }

    private var amountError: String? {
        guard !amountText.isEmpty else { return nil }
        switch amount {
        case .failure(let e): return e.localizedDescription
        case .success(let units): return Amounts.compare(units, available) == .orderedDescending ? "more than available" : nil
        }
    }

    private var recipientError: String? {
        guard mode == .send, !to.isEmpty else { return nil }
        if !PaymentRequest.isAddress(to) { return "an Aztec address is 0x followed by 64 hexadecimal characters" }
        return to.lowercased() == session.account?.address.lowercased() ? "this is your own address" : nil
    }

    private var valid: Bool {
        (try? amount.get()) != nil && amountError == nil && recipientError == nil && (mode == .shield || !to.isEmpty)
    }

    var body: some View {
        if session.tokens.isEmpty {
            ContentUnavailableView("No token yet", systemImage: "circle.dashed", description: Text(noTokens))
                .navigationTitle(mode == .send ? "Send" : "Shield")
        } else {
            form
        }
    }

    private var form: some View {
        Form {
            // Send: private spends private notes. Shield: "make private" spends the public side.
            if mode == .send {
                PrivacySwitch(isPrivate: Binding(get: { !publicSide }, set: { publicSide = !$0 }))
            } else {
                PrivacySwitch(isPrivate: $publicSide, labels: ("Make private", "Make public"))
            }
            Picker("Token", selection: $tokenIndex) {
                ForEach(session.tokens.indices, id: \.self) { i in Text("\(session.tokens[i].info.name) (\(session.tokens[i].info.symbol))").tag(i) }
            }
            if mode == .send { recipientSection }
            amountSection
            Section { Text(explanation).font(.footnote).foregroundStyle(.secondary) }
        }
        .navigationTitle(mode == .send ? "Send" : "Shield")
        .safeAreaInset(edge: .bottom) {
            Button(actionLabel, action: submit).buttonStyle(PrimaryButtonStyle()).disabled(!valid || model.busy).padding()
        }
        .onAppear { if mode == .shield { publicSide = Amounts.isPositive(session.tokens.first?.balance?.public ?? "0") } }
    }

    private var recipientSection: some View {
        RecipientSection(placeholder: "Recipient address", to: $to, note: recipientError ?? scanNote, onScan: apply)
            // The note is about the scanned address: editing it to another one drops the note.
            .onChange(of: to) { if to != scanned { scanNote = nil } }
    }

    private var amountSection: some View {
        Section {
            HStack {
                TextField("Amount", text: $amountText).keyboardType(.decimalPad)
                Text(token?.info.symbol ?? "").foregroundStyle(.secondary)
                Button("Max") { amountText = Amounts.format(available, decimals: token?.info.decimals ?? 0, maxFraction: 36).replacingOccurrences(of: ",", with: "") }
                    .disabled(!Amounts.isPositive(available))
            }
        } footer: {
            Text(amountError ?? "Available: \(Amounts.format(available, decimals: token?.info.decimals ?? 0)) \(token?.info.symbol ?? "")")
                .foregroundStyle(amountError == nil ? Color.secondary : Palette.danger)
        }
    }

    private var explanation: String {
        switch kind {
        case .private: "Private: the amount, the sender and the recipient stay hidden. Proven on this phone."
        case .public: "Public: visible to everyone on chain, like a transparent transfer."
        case .shield: "Moves public funds into your private balance. The amount is visible once, then hidden."
        case .unshield: "Moves private funds to your public balance, where everyone can see them."
        }
    }

    private var actionLabel: String {
        switch kind {
        case .private: "Send privately"
        case .public: "Send publicly"
        case .shield: "Make private"
        case .unshield: "Make public"
        }
    }

    private func submit() {
        guard let token, let units = try? amount.get() else { return }
        let kind = kind, to = to
        Task { await model.run(done: "Sent. It will appear in your balance once in a block.") { try await $0.send(kind, token: token.info, to: to, amount: units) } }
        dismiss()
    }

    /// Fills the form from a scanned QR code, and says what to check about it.
    private func apply(_ text: String) {
        guard let request = PaymentRequest.parse(text) else { scanNote = "This QR code is not an Aztec address"; return }
        scanned = request.address
        to = request.address
        let index = request.token.flatMap { t in session.tokens.firstIndex { $0.info.address.lowercased() == t.lowercased() } }
        if let index { tokenIndex = index }
        if let amount = request.amount { amountText = amount }
        if let network = request.network, network != session.network.id {
            scanNote = "This request is for \(network), not \(session.network.id): check before sending"
        } else if request.token != nil && index == nil {
            scanNote = "The requested token is not in your list: add it first"
        }
    }
}

/// An address to type, paste or scan; it wraps, so the whole address shows.
struct RecipientSection: View {
    let placeholder: String
    @Binding var to: String
    /// An error or a remark about a scanned code, shown under the field.
    let note: String?
    let onScan: (String) -> Void
    @State private var scanning = false

    var body: some View {
        Section {
            HStack {
                TextField(placeholder, text: $to, axis: .vertical).lineLimit(1...3).font(.mono)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button { scanning = true } label: { Image(systemName: "qrcode.viewfinder") }.buttonStyle(.borderless).accessibilityLabel("Scan a QR code")
                Button { to = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? to } label: { Image(systemName: "doc.on.clipboard") }
                    .buttonStyle(.borderless).accessibilityLabel("Paste")
            }
            .fullScreenCover(isPresented: $scanning) { QrScanner(onResult: onScan) }
        } footer: {
            if let note { Text(note).foregroundStyle(Palette.danger) }
        }
    }
}
