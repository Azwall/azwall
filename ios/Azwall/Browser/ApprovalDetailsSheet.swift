import AzwallKit
import SwiftUI

/// A dApp request in full, opened from its panel: every contract and function a site asks for, every call and
/// argument of a transaction, its fee and attachments, and the raw request (folded: it is long). Values are whole and
/// can be selected and copied. The request can be answered from here too.
struct ApprovalDetailsSheet: View {
    let request: ApprovalRequest
    let onDecide: (Bool) -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(siteOf(request.origin)).font(.headline)
                    ForEach(Array(request.sections.enumerated()), id: \.offset) { _, section in
                        if section.title == "Raw request" {
                            DisclosureGroup(section.title) { rows(section) }.padding(14).background(card)
                        } else {
                            VStack(alignment: .leading, spacing: 10) {
                                Text(section.title).font(.subheadline.bold())
                                rows(section)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                            .background(card)
                        }
                    }
                }
                .padding(20)
            }
            .background(Palette.background)
            .navigationTitle(request.title)
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) { buttons }
        }
        .presentationDetents([.large])
    }

    private func rows(_ section: ApprovalSection) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(section.rows.enumerated()), id: \.offset) { _, row in DetailRow(label: row.label, value: row.value) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var card: some View { RoundedRectangle(cornerRadius: 16).fill(Palette.card) }

    private var buttons: some View {
        HStack(spacing: 12) {
            Button(request.kind.rejectLabel) { onDecide(false) }.buttonStyle(.bordered).frame(maxWidth: .infinity)
            Button(request.kind.approveLabel) { onDecide(true) }.buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
        }
        .controlSize(.large)
        .padding(.horizontal, 20).padding(.vertical, 12)
        .background(.bar)
    }
}
