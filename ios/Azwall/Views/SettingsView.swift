import AzwallKit
import AzwallRust
import SwiftUI

struct SettingsView: View {
    let model: AppModel
    let session: WalletSession
    let onMenu: () -> Void
    @State private var name = ""
    @State private var revealed: String?
    @State private var phrase: String?
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmWipe = false
    @AppStorage(BrowserTabs.engineKey) private var engine = SearchEngine.default.rawValue

    var body: some View {
        Form {
            accountSection
            networkSection
            Section {
                Picker("Search engine", selection: $engine) { ForEach(SearchEngine.allCases) { Text($0.label).tag($0.rawValue) } }
            } header: {
                Text("Browser")
            } footer: {
                Text("Where the Explore address bar searches: only engines that do not profile who searches.")
            }
            securitySection
            Section("About") {
                LabeledContent("Azwall", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")
                LabeledContent("Rust core", value: AzwallRuntime.coreVersion)
                LabeledContent("Provers", value: AzwallRuntime.proverReleases.joined(separator: ", "))
            }
        }
        .navigationTitle("Settings")
        .menuButton(onMenu)
        // Leaving the app hides a revealed key: it must not wait on screen for whoever opens the app next.
        .onChange(of: scenePhase) { if scenePhase != .active { revealed = nil; phrase = nil } }
        .sheet(isPresented: .constant(phrase != nil), onDismiss: { phrase = nil }) {
            if let phrase { PhraseSheet(words: phrase.split(separator: " ").map(String.init)) { self.phrase = nil } }
        }
        .onAppear { name = session.account?.alias ?? "" }
        .alert("Secret key", isPresented: .constant(revealed != nil), presenting: revealed) { key in
            Button("Copy") { copySecret(key); revealed = nil }
            Button("Done", role: .cancel) { revealed = nil }
        } message: { key in
            Text("Anyone with this key controls the account. Never share it.\n\n\(key)")
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

    private var securitySection: some View {
        Section("Security") {
            if model.hasRecoveryPhrase { Button("Show recovery phrase") { Task { phrase = await model.revealPhrase() } } }
            Button("Lock wallet") { Task { await model.lock() } }
            Button("Remove wallet from this device", role: .destructive) { confirmWipe = true }
        }
    }
}

/// The recovery phrase once, until dismissed; no copy button: it is meant to be written down.
private struct PhraseSheet: View {
    let words: [String]
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Anyone with these words controls every account of this wallet. Never share them.").foregroundStyle(Palette.danger)
                    PhraseGrid(words: words)
                }
                .padding(24)
            }
            .navigationTitle("Recovery phrase")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done", action: onDone) }
        }
    }
}
