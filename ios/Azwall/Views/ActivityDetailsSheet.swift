import AzwallKit
import SwiftUI

/// One transaction of the history in full: tapping a row of Activity opens it. Values can be selected and copied.
struct ActivityDetailsSheet: View {
    let item: ActivityItem

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(ActivityDetails.of(item, date: item.createdAt.formatted(date: .abbreviated, time: .shortened))) {
                        DetailRow(label: $0.label, value: $0.value)
                    }
                    if let hash = item.txHash {
                        Button("Copy transaction hash") { UIPasteboard.general.string = hash }.frame(maxWidth: .infinity, minHeight: 44)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
            }
            .navigationTitle(activityTitle(item))
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.large])
    }
}
