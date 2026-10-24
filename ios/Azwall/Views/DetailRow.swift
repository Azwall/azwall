import SwiftUI

/// One line of a detail sheet (confirmation, transaction, dApp request): its label, then its value in full, selectable.
/// Addresses, hashes and raw JSON in a fixed-width font: the place to check them character by character.
struct DetailRow: View {
    let label: String
    let value: String

    private var mono: Bool { value.hasPrefix("0x") || label == "JSON" }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value)
                .font(mono ? .mono : .body)
                // No hyphen where a long address wraps: it would read as part of the value.
                .typesettingLanguage(mono ? .init(identifier: "zxx") : .init(identifier: Locale.current.identifier))
                .textSelection(.enabled)
        }
    }
}
