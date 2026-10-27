import AzwallKit
import SwiftUI
import UIKit

/// What went wrong lately (DiagnosticLog), shared only when the user chooses to: no service receives it on its own.
struct DiagnosticsSection: View {
    @State private var file: URL?
    @State private var count = DiagnosticLog.shared.entries.count

    var body: some View {
        Section {
            LabeledContent("Recorded problems", value: "\(count)")
            if let file {
                ShareLink(item: file) { Label("Export diagnostic log", systemImage: "square.and.arrow.up") }
            }
            Button("Clear diagnostic log", role: .destructive) {
                DiagnosticLog.shared.clear()
                refresh()
            }
            .disabled(count == 0)
        } header: {
            Text("Diagnostics")
        } footer: {
            Text("Warnings and errors of the last days, kept on this phone only. Addresses and hashes are shortened and RPC links cut to their host; no keys or recovery words are ever in it. You choose whom to send it to.")
        }
        .onAppear(perform: refresh)
    }

    /// The export as a file: the share sheet offers Mail, Messages, Files… with a readable name.
    private func refresh() {
        count = DiagnosticLog.shared.entries.count
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        let text = DiagnosticLog.shared.export(app: "Azwall \(version) (\(build))", system: "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("azwall-diagnostics.txt")
        // Without the file there is nothing to share: the button is then left out rather than failing.
        file = (try? text.write(to: url, atomically: true, encoding: .utf8)).map { url }
    }
}
