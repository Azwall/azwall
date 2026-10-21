import AzwallKit
import SwiftUI

/// Every embedded network; each runs its own Aztec version.
struct NetworksSheet: View {
    let model: AppModel
    let current: String
    let close: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(model.networks) { n in
                        Button {
                            close()
                            Task { await model.switchNetwork(n.id) }
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(n.label).foregroundStyle(.primary)
                                    Text("Aztec \(n.aztecVersion) · \(n.rpcHost)").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if n.id == current { Image(systemName: "checkmark").foregroundStyle(Palette.accent) }
                            }
                        }
                        .disabled(n.id == current)
                    }
                } footer: {
                    Text("Your keys are the same on every network, but each network runs its own Aztec version: your address, balances and history are different on each.")
                }
            }
            .navigationTitle("Networks")
        }
    }
}

struct AccountsSheet: View {
    let model: AppModel
    let session: WalletSession
    let close: () -> Void
    @State private var alias = ""
    @State private var key = ""

    private var keyError: String? {
        guard !key.isEmpty else { return nil }
        do { _ = try Vault.parseSigningKey(key); return nil } catch { return error.localizedDescription }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(session.accounts) { a in
                    Button {
                        close()
                        Task { await session.select(a.address) }
                    } label: {
                        HStack(spacing: 12) {
                            AccountAvatar(address: a.address)
                            VStack(alignment: .leading) {
                                Text(a.alias).foregroundStyle(.primary)
                                Text(shortAddress(a.address)).font(.mono).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if a.address == session.selected { Image(systemName: "checkmark").foregroundStyle(Palette.accent) }
                        }
                    }
                }
                Section("Add or import an account") {
                    TextField("Name (Account \(session.accounts.count + 1))", text: $alias)
                    TextField("Signing key to import (empty: next account of your phrase)", text: $key).font(.mono).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if let keyError { Text(keyError).font(.caption).foregroundStyle(Palette.danger) }
                    Button(key.isEmpty ? "Create account" : "Import account") {
                        let alias = alias, imported = key.isEmpty ? nil : key
                        close()
                        Task { await model.run { try await $0.addAccount(alias: alias, importedKey: imported) } }
                    }
                    .disabled(keyError != nil)
                }
            }
            .navigationTitle("Accounts")
        }
    }
}

/// Follow a token by its contract address, typed, pasted or scanned from a shared token's QR code; the rest is read on chain.
struct AddTokenSheet: View {
    let model: AppModel
    let network: String
    let close: () -> Void
    @State private var text = ""
    @State private var scanning = false

    private var request: PaymentRequest? { PaymentRequest.parse(text) }
    private var note: String? { !text.isEmpty && request == nil ? "Not a token address" : request?.networkNote(current: network) }

    var body: some View {
        NavigationStack {
            Form {
                HStack {
                    TextField("Token contract address", text: $text).font(.mono).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button { scanning = true } label: { Image(systemName: "qrcode.viewfinder") }.buttonStyle(.borderless).accessibilityLabel("Scan a token QR code")
                }
                if let note { Text(note).foregroundStyle(Palette.danger) }
                Button("Add token") {
                    guard let token = request?.tokenAddress else { return }
                    close()
                    Task { await model.run(done: "Token added.") { try await $0.addToken(token) } }
                }
                .disabled(request == nil)
            }
            .navigationTitle("Add a token")
            .fullScreenCover(isPresented: $scanning) { QrScanner(hint: "Point at a token QR code") { text = $0.trimmingCharacters(in: .whitespacesAndNewlines) } }
        }
    }
}
