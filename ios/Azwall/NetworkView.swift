import AzwallKit
import SwiftUI

struct NetworkView: View {
    let model: WalletModel

    var body: some View {
        NavigationStack {
            List {
                if let error = model.startupError {
                    Text(error).foregroundStyle(.red)
                } else {
                    networkSection
                    if let network = model.selected { statusSection(network) }
                }
            }
            .navigationTitle("Azwall")
        }
    }

    private var networkSection: some View {
        Section("Network") {
            ForEach(model.networks) { network in
                Button {
                    Task { await model.select(network.id) }
                } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(network.label)
                            Text("Aztec \(network.aztecVersion) · \(network.rpcHost)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if network.id == model.selectedId { Image(systemName: "checkmark") }
                    }
                }
                .foregroundStyle(.primary)
            }
        }
    }

    private func statusSection(_ network: NetworkInfo) -> some View {
        Section("Status") {
            LabeledContent("Node", value: nodeText)
            LabeledContent("Barretenberg \(network.barretenbergVersion)", value: proverText(network))
            Button("Load prover") { Task { await model.prepareProver() } }
                .disabled(!network.proverSupported || model.prover == .running)
        }
    }

    private var nodeText: String {
        switch model.node {
        case .idle: "Not checked"
        case .running: "Checking…"
        case .failed(let message): "Unreachable: \(message)"
        case .done(let check):
            check.compatible ? "Compatible (node \(check.nodeVersion))" : "Incompatible: \(check.mismatches.joined(separator: ", "))"
        }
    }

    private func proverText(_ network: NetworkInfo) -> String {
        guard network.proverSupported else { return "Not supported by this build" }
        switch model.prover {
        case .idle: return "Not loaded"
        case .running: return "Loading…"
        case .done: return "Ready"
        case .failed(let message): return message
        }
    }
}
