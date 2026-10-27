import AzwallKit
import SwiftUI

/// First run: create a wallet (back its 24 words up, then check three of them) or restore one.
struct OnboardingView: View {
    let model: AppModel
    @State private var path: [Step] = []

    /// The words travel in the step: a destination reading the parent's state can be built with a stale copy of it.
    enum Step: Hashable { case backup([String]), verify([String]), restore }

    var body: some View {
        NavigationStack(path: $path) {
            welcome
                .navigationDestination(for: Step.self) { step in
                    switch step {
                    case .backup(let words): PhraseBackupView(words: words) { path.append(.verify(words)) }
                    case .verify(let words): PhraseVerifyView(words: words, busy: model.busy) { create(words.joined(separator: " ")) }
                    case .restore: RestoreView(busy: model.busy, check: model.checkSecret) { create($0) }
                    }
                }
        }
    }

    private func create(_ secret: String) {
        Task { await model.createWallet(secret: secret, alias: "Account 1") }
    }

    private func start() {
        Task {
            do {
                let words = try await model.newRecoveryPhrase()
                path.append(.backup(words.split(separator: " ").map(String.init)))
            } catch {
                // The phrase itself is never part of the error: only why none could be made.
                model.message = error.localizedDescription
            }
        }
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 20) {
            Spacer()
            Image("Logo").resizable().scaledToFit().frame(height: 72).frame(maxWidth: .infinity)
            Text("Azwall").font(.largeTitle.bold()).frame(maxWidth: .infinity)
            Text("A private wallet for Aztec.").font(.title3).foregroundStyle(.secondary)
            Feature(icon: "lock", title: "Private by default", text: "Balances and transfers stay encrypted. Only you can see them.")
            Feature(icon: "bolt", title: "Proven on your phone", text: "Every transaction is proven on this device, natively, in seconds.")
            Feature(icon: "point.3.connected.trianglepath.dotted", title: "Every Aztec network", text: "Switch between networks running different Aztec versions.")
            Spacer()
            Button("Create a new wallet", action: start).buttonStyle(PrimaryButtonStyle())
            Button("Restore a wallet") { path.append(.restore) }.frame(maxWidth: .infinity, minHeight: 44)
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

/// Shown when a vault exists: one tap, then Face ID, Touch ID or the passcode.
struct LockView: View {
    let model: AppModel

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image("Logo").resizable().scaledToFit().frame(height: 120)
            Text("Azwall").font(.largeTitle.bold())
            Text("Your wallet is locked").foregroundStyle(.secondary)
            Spacer()
            Button(model.busy ? "Unlocking…" : "Unlock") { Task { await model.unlock() } }.buttonStyle(PrimaryButtonStyle()).disabled(model.busy)
        }
        .padding(24)
        .background(Palette.background)
    }
}
