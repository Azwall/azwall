import AzwallKit
import SwiftUI

/// The confirmation before a transaction is proven and sent: every detail once more, and nothing happens until
/// Confirm.
struct ReviewSheet: View {
    let review: Review
    let onConfirm: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(review.lines) { line in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(line.label).font(.caption).foregroundStyle(.secondary)
                            // An address in full and in monospace: the one place to check it character by character.
                            Text(line.value).font(line.value.hasPrefix("0x") ? .mono : .body).textSelection(.enabled)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
            }
            .navigationTitle(review.title)
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 4) {
                    Button(review.confirm) { dismiss(); onConfirm() }.buttonStyle(PrimaryButtonStyle())
                    Button("Cancel", role: .cancel) { dismiss() }.frame(maxWidth: .infinity, minHeight: 44)
                }
                .padding()
            }
        }
        .presentationDetents([.large])
    }
}
