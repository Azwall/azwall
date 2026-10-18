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
                    TextField("Signing key to import (optional)", text: $key).font(.mono).textInputAutocapitalization(.never)
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

/// Follow a token by its contract address; name, symbol and decimals are read on chain.
struct AddTokenSheet: View {
    let model: AppModel
    let close: () -> Void
    @State private var address = ""

    private var valid: Bool { address.count == 66 && address.hasPrefix("0x") && address.dropFirst(2).allSatisfy(\.isHexDigit) }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Token contract address", text: $address).font(.mono).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Add token") {
                    let token = address
                    close()
                    Task { await model.run(done: "Token added.") { try await $0.addToken(token) } }
                }
                .disabled(!valid)
            }
            .navigationTitle("Add a token")
        }
    }
}
