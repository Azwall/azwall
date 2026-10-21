import AzwallKit
import SwiftUI

/// The words, numbered, in two columns: the order matters as much as the words.
struct PhraseGrid: View {
    let words: [String]

    var body: some View {
        let half = (words.count + 1) / 2
        HStack(alignment: .top, spacing: 16) {
            ForEach([0..<half, half..<words.count], id: \.lowerBound) { range in
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(range, id: \.self) { i in
                        HStack(spacing: 10) {
                            Text("\(i + 1)").foregroundStyle(.secondary).frame(width: 22, alignment: .trailing)
                            Text(words[i])
                        }
                        .font(.mono)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Palette.card))
    }
}

struct PhraseBackupView: View {
    let words: [String]
    let onNext: () -> Void
    @State private var saved = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Your recovery phrase").font(.title.bold())
                Text("These \(words.count) words are your wallet: every account it creates comes back from them. Write them down in order and keep them offline. Anyone who has them controls your funds; without them a lost phone means lost funds.")
                    .foregroundStyle(.secondary)
                PhraseGrid(words: words)
                Toggle("I wrote the \(words.count) words down, in order", isOn: $saved)
            }
            .padding(24)
        }
        .safeAreaInset(edge: .bottom) { Button("Next", action: onNext).buttonStyle(PrimaryButtonStyle()).disabled(!saved).padding(24) }
        .background(Palette.background)
    }
}

/// Three words asked back, at random places: a phrase copied wrong is found now, not on the day it is needed.
struct PhraseVerifyView: View {
    let words: [String]
    let busy: Bool
    let onDone: () -> Void
    @State private var asked: [Int] = []
    @State private var answers = ["", "", ""]

    private var right: Bool {
        asked.count == 3 && asked.indices.allSatisfy { answers[$0].trimmingCharacters(in: .whitespaces).lowercased() == words[asked[$0]] }
    }

    var body: some View {
        Form {
            Section {
                ForEach(asked.indices, id: \.self) { n in
                    TextField("Word \(asked[n] + 1)", text: $answers[n]).textInputAutocapitalization(.never).autocorrectionDisabled().font(.mono)
                }
            } header: {
                Text("Type these words from what you wrote down.")
            }
        }
        .navigationTitle("Check your copy")
        .safeAreaInset(edge: .bottom) {
            Button(busy ? "Creating…" : "Create wallet", action: onDone).buttonStyle(PrimaryButtonStyle()).disabled(!right || busy).padding(24)
        }
        .onAppear { if asked.isEmpty { asked = Array(words.indices.shuffled().prefix(3)).sorted() } }
    }
}

/// Restores from a recovery phrase (account 1; add accounts again to bring the next ones back) or a signing key.
struct RestoreView: View {
    let busy: Bool
    let check: (String) async -> String?
    let onRestore: (String) -> Void
    @State private var input = ""
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Restore a wallet").font(.title.bold())
            Text("Type your recovery phrase, words separated by spaces. Accounts you added come back when you add them again, in the same order. A 64-character signing key works too.")
                .foregroundStyle(.secondary)
            TextField("Recovery phrase or signing key", text: $input, axis: .vertical)
                .lineLimit(4...8).font(.mono).textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(12).background(RoundedRectangle(cornerRadius: 12).stroke(error == nil ? Color.secondary.opacity(0.4) : Palette.danger))
            if let error { Text(error).font(.caption).foregroundStyle(Palette.danger) }
            Spacer()
            Button(busy ? "Restoring…" : "Restore") { onRestore(input) }.buttonStyle(PrimaryButtonStyle())
                .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || error != nil || busy)
        }
        .padding(24)
        .background(Palette.background)
        // Checked once typing pauses: a phrase is only valid once all its words are there.
        .task(id: input) {
            error = nil
            guard !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            error = await check(input)
        }
    }
}
