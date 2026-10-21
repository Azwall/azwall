import AzwallKit
import SwiftUI

/// First run: create a wallet (and back its key up) or import an existing key.
struct OnboardingView: View {
    let model: AppModel
    @State private var path: [Step] = []
    @State private var newKey = Vault.newSigningKey()

    enum Step: Hashable { case backup, importKey }

    var body: some View {
        NavigationStack(path: $path) {
            welcome
                .navigationDestination(for: Step.self) { step in
                    switch step {
                    case .backup: BackupView(key: newKey, busy: model.busy) { Task { await model.createWallet(signingKey: newKey, alias: "Account 1") } }
                    case .importKey: ImportView(busy: model.busy) { key in Task { await model.createWallet(signingKey: key, alias: "Account 1") } }
                    }
                }
        }
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 20) {
            Spacer()
            Image(systemName: "shield").font(.system(size: 52)).foregroundStyle(Palette.privateFunds)
            Text("Azwall").font(.largeTitle.bold())
            Text("A private wallet for Aztec.").font(.title3).foregroundStyle(.secondary)
            Feature(icon: "lock", title: "Private by default", text: "Balances and transfers stay encrypted. Only you can see them.")
            Feature(icon: "bolt", title: "Proven on your phone", text: "Every transaction is proven on this device, natively, in seconds.")
            Feature(icon: "point.3.connected.trianglepath.dotted", title: "Every Aztec network", text: "Switch between networks running different Aztec versions.")
            Spacer()
            Button("Create a new wallet") { path.append(.backup) }.buttonStyle(PrimaryButtonStyle())
            Button("I already have a key") { path.append(.importKey) }.frame(maxWidth: .infinity, minHeight: 44)
        }
        .padding(24)
        .background(Palette.background)
    }
}

private struct Feature: View {
    let icon: String
    let title: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: icon).foregroundStyle(Palette.accent).frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

private struct BackupView: View {
    let key: String
    let busy: Bool
    let onDone: () -> Void
    @State private var saved = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Back up your key").font(.title.bold())
            Text("This key is your account. Write it down and keep it offline: anyone who has it controls your funds, and without it a lost phone means lost funds.")
                .foregroundStyle(.secondary)
            Card { Text(groupKey(key)).font(.system(.body, design: .monospaced)) }
            Button("Copy for one minute") { copySecret(key) }
            Toggle("I wrote my key down somewhere safe", isOn: $saved)
            Spacer()
            Button(busy ? "Creating…" : "Create wallet", action: onDone).buttonStyle(PrimaryButtonStyle()).disabled(!saved || busy)
        }
        .padding(24)
        .background(Palette.background)
    }
}

private struct ImportView: View {
    let busy: Bool
    let onImport: (String) -> Void
    @State private var input = ""

    private var error: String? {
        guard !input.isEmpty else { return nil }
        do { _ = try Vault.parseSigningKey(input); return nil } catch { return error.localizedDescription }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Import your key").font(.title.bold())
            Text("Paste the 64-character signing key of your Aztec account.").foregroundStyle(.secondary)
            TextField("Signing key", text: $input, axis: .vertical)
                .font(.mono).textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(12).background(RoundedRectangle(cornerRadius: 12).stroke(error == nil ? Color.secondary.opacity(0.4) : Palette.danger))
            if let error { Text(error).font(.caption).foregroundStyle(Palette.danger) }
            Spacer()
            Button(busy ? "Importing…" : "Import") { onImport(input) }.buttonStyle(PrimaryButtonStyle()).disabled(input.isEmpty || error != nil || busy)
        }
        .padding(24)
        .background(Palette.background)
    }
}

/// Shown when a vault exists: one tap, then Face ID, Touch ID or the passcode.
struct LockView: View {
    let model: AppModel

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "shield").font(.system(size: 64)).foregroundStyle(Palette.privateFunds)
            Text("Azwall").font(.largeTitle.bold())
            Text("Your wallet is locked").foregroundStyle(.secondary)
            Spacer()
            Button(model.busy ? "Unlocking…" : "Unlock") { Task { await model.unlock() } }.buttonStyle(PrimaryButtonStyle()).disabled(model.busy)
        }
        .padding(24)
        .background(Palette.background)
    }
}
