import AzwallKit
import AzwallRust
import SwiftUI

struct SettingsView: View {
    let model: AppModel
    let session: WalletSession
    @State private var name = ""
    @State private var revealed: String?
    @State private var confirmWipe = false
    @State private var tokenName = "Test Token"
    @State private var tokenSymbol = "TST"
    @State private var mintAmount = "1000"
    @State private var mintPrivate = true

    var body: some View {
        Form {
            accountSection
            networkSection
            if session.network.id.hasPrefix("testnet") && session.account?.deployed == true { testnetSection }
            securitySection
            Section("About") {
                LabeledContent("Azwall", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")
                LabeledContent("Rust core", value: coreVersion())
                LabeledContent("Provers", value: supportedBarretenbergReleases().joined(separator: ", "))
            }
        }
        .navigationTitle("Settings")
        .onAppear { name = session.account?.alias ?? "" }
        .alert("Secret key", isPresented: .constant(revealed != nil), presenting: revealed) { key in
            Button("Copy") { UIPasteboard.general.string = key; revealed = nil }
            Button("Done", role: .cancel) { revealed = nil }
        } message: { key in
            Text("Anyone with this key controls the account. Never share it.\n\n\(groupKey(key))")
        }
        .confirmationDialog("Remove this wallet?", isPresented: $confirmWipe, titleVisibility: .visible) {
            Button("Remove", role: .destructive) { Task { await model.wipe() } }
        } message: {
            Text("Your keys are deleted from this phone. Without your key backup, the funds are lost for good.")
        }
    }

    private var accountSection: some View {
        Section("Account") {
            HStack {
                TextField("Name", text: $name)
                Button("Save") {
                    guard let address = session.account?.address else { return }
                    let alias = name
                    Task { await model.run { try await $0.renameAccount(address, alias: alias) } }
                }
                .disabled(name.isEmpty || name == session.account?.alias)
            }
            LabeledContent("Activated", value: session.account?.deployed == true ? "yes" : "not yet")
            Button("Show secret key") {
                guard let address = session.account?.address else { return }
                Task { revealed = await model.revealKey(address: address) }
            }
        }
    }

    private var networkSection: some View {
        Section("Network") {
            LabeledContent("Network", value: session.network.label)
            LabeledContent("Aztec", value: session.network.aztecVersion)
            LabeledContent("Node", value: session.check?.nodeVersion ?? "—")
            LabeledContent("Block", value: session.check.map { String($0.blockNumber) } ?? "—")
            LabeledContent("RPC", value: session.network.rpcHost)
            if let node = model.nodes[session.network.id] {
                Text(node.compatible ? "Node compatible" : "Node incompatible: \(node.mismatches.joined(separator: ", "))")
                    .foregroundStyle(node.compatible ? Palette.success : Palette.danger)
            }
            Button("Check node") { Task { await model.checkNode(session.network.id) } }
            LabeledContent("Prover", value: "Barretenberg \(session.network.barretenbergVersion)")
            Button("Load prover now") { Task { await model.loadProver(session.network.id) } }
        }
    }

    /// Test networks have no faucet for arbitrary tokens: create one with this account as minter, then mint.
    private var testnetSection: some View {
        Section("Testnet tools") {
            TextField("Name", text: $tokenName)
            TextField("Symbol", text: $tokenSymbol).textInputAutocapitalization(.characters)
            Button("Create test token") {
                let name = tokenName, symbol = tokenSymbol
                Task { await model.run(done: "Token creation sent.") { try await $0.deployTestToken(name: name, symbol: symbol) } }
            }
            .disabled(model.busy || tokenName.isEmpty || tokenSymbol.isEmpty)
            if let token = session.tokens.first?.info {
                TextField("Amount", text: $mintAmount).keyboardType(.decimalPad)
                Toggle("Mint privately", isOn: $mintPrivate)
                Button("Mint \(token.symbol)") { mint(token) }.disabled(model.busy)
            }
        }
    }

    private var securitySection: some View {
        Section("Security") {
            Button("Lock wallet") { Task { await model.lock() } }
            Button("Remove wallet from this device", role: .destructive) { confirmWipe = true }
        }
    }

    private func mint(_ token: TokenInfo) {
        let amount = mintAmount, toPrivate = mintPrivate
        Task {
            await model.run(done: "Mint sent.") { session in
                try await session.mint(token: token, amount: try Amounts.parse(amount, decimals: token.decimals), toPrivate: toPrivate)
            }
        }
    }
}
