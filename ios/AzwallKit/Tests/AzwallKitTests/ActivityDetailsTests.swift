import Testing

@testable import AzwallKit

struct ActivityDetailsTests {
    let to = "0x" + String(repeating: "cd", count: 32)
    let hash = "0x" + String(repeating: "ef", count: 32)

    var sent: ActivityItem {
        var item = ActivityItem(account: "0xme", kind: .sendPrivate, status: .confirmed)
        item.txHash = hash
        item.token = "0xt"
        item.symbol = "TST"
        item.decimals = 6
        item.amount = "12500001"
        item.counterparty = to
        item.blockNumber = 42
        return item
    }

    func value(_ lines: [Review.Line], _ label: String) -> String? { lines.first { $0.label == label }?.value }

    @Test func aSentTransferShowsItsExactAmountRecipientHashAndBlock() {
        let lines = ActivityDetails.of(sent, date: "21 Oct 2026, 20:30")
        #expect(value(lines, "Status") == "Confirmed in block 42")
        #expect(value(lines, "Amount") == "12.500001 TST")
        #expect(value(lines, "To") == to)
        #expect(value(lines, "Transaction hash") == hash)
        #expect(value(lines, "Error") == nil)
    }

    @Test func aFailedOrReceivedOneSaysSo() {
        var failed = sent
        failed.status = .failed
        failed.error = "Cancelled"
        #expect(value(ActivityDetails.of(failed, date: "d"), "Error") == "Cancelled")
        let received = ActivityItem(account: "0xme", kind: .receive, status: .confirmed)
        #expect(value(ActivityDetails.of(received, date: "d"), "Status") == "Confirmed")
    }
}
