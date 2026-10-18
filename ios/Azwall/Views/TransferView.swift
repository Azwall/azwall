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
        if to.count != 66 || !to.hasPrefix("0x") || !to.dropFirst(2).allSatisfy(\.isHexDigit) { return "an Aztec address is 0x followed by 64 hexadecimal characters" }
        return to.lowercased() == session.account?.address.lowercased() ? "this is your own address" : nil
    }

    private var valid: Bool {
        (try? amount.get()) != nil && amountError == nil && recipientError == nil && (mode == .shield || !to.isEmpty)
    }

    var body: some View {
        Form {
            Picker("", selection: $publicSide) {
                Text(mode == .send ? "Private" : "Make private").tag(mode == .shield)
                Text(mode == .send ? "Public" : "Make public").tag(mode == .send)
            }
            .pickerStyle(.segmented)
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
        Section {
            HStack {
                TextField("Recipient address", text: $to).font(.mono).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button { to = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? to } label: { Image(systemName: "doc.on.clipboard") }
            }
        } footer: {
            if let recipientError { Text(recipientError).foregroundStyle(Palette.danger) }
        }
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
}
