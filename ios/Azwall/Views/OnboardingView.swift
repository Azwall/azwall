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
            Text("Your wallet for the Aztec network.").font(.title3).foregroundStyle(.secondary)
            Feature(icon: "lock", title: "Private by default", text: "Balances and transfers stay encrypted. Only you can see them.")
            Feature(icon: "bolt", title: "Proven on your phone", text: "Every transaction is proven on this device, natively, in seconds.")
            Feature(icon: "point.3.connected.trianglepath.dotted", title: "Every Aztec network", text: "Switch between networks running different Aztec versions.")
            Spacer()
            Button("Create a new wallet", action: start).buttonStyle(PrimaryButtonStyle())
            Button("Restore a wallet") { path.append(.restore) }.frame(maxWidth: .infinity, minHeight: 44)
            // Before anything is created, as the stores ask: what the wallet keeps and sends.
            Link("Privacy policy", destination: Browsing.privacyPolicy).font(.subheadline)
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

/// A wallet was created on this iPhone but iOS erased its vault: the passcode was turned off, or a backup was restored
/// to another device (the vault never leaves the device it was made on). Said, rather than a welcome screen as if the
/// wallet had never been here.
struct VaultLostView: View {
    let model: AppModel
    @State private var confirming = false

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Text("Your wallet's keys are no longer on this iPhone").font(.title2.bold()).multilineTextAlignment(.center)
            Text("iOS erased them: the passcode was turned off, or this iPhone was restored from another one's backup. Your funds are safe on the network: restore the wallet with your recovery phrase.")
                .multilineTextAlignment(.center).foregroundStyle(.secondary)
            Spacer()
            Button("Restore with my recovery phrase") { confirming = true }.buttonStyle(PrimaryButtonStyle()).disabled(model.busy)
        }
        .padding(24)
        .background(Palette.background)
        .alert("Start over?", isPresented: $confirming) {
            Button("Start over", role: .destructive) { Task { await model.startOver() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("What is left of the old wallet on this iPhone is removed. Without your recovery phrase, its funds cannot be recovered.")
        }
    }
}
