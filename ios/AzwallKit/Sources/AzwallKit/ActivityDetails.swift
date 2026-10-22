import Foundation

/// Everything the wallet knows about one transaction of the history, line by line, for its detail sheet: the exact
/// amount, addresses and hash in full (to check them or look them up elsewhere). `date` comes formatted by the app.
public enum ActivityDetails {
    public static func of(_ item: ActivityItem, date: String) -> [Review.Line] {
        var lines: [(String, String)] = [("Status", status(item))]
        if item.status == .failed { lines.append(("Error", Errors.friendly(item.error))) }
        if let amount = exactAmount(item) { lines.append(("Amount", amount)) }
        if let token = item.token { lines.append(("Token", token)) }
        if let other = item.counterparty { lines.append((item.kind == .receive ? "From" : "To", other)) }
        lines.append(("Account", item.account))
        if let origin = item.origin { lines.append(("Site", origin)) }
        if let summary = item.summary { lines.append(("Action", summary)) }
        if let hash = item.txHash { lines.append(("Transaction hash", hash)) }
        lines.append(("Date", date))
        return lines.map { Review.Line(label: $0.0, value: $0.1) }
    }

    private static func status(_ item: ActivityItem) -> String {
        switch item.status {
        case .proving: "Being proven on this phone"
        case .pending: "Sent, waiting for a block"
        case .confirmed: item.blockNumber.map { "Confirmed in block \($0)" } ?? "Confirmed"
        case .failed: "Failed"
        }
    }

    /// Every decimal, unlike the list's rounded amount.
    private static func exactAmount(_ item: ActivityItem) -> String? {
        guard let units = item.amount else { return nil }
        let shown = item.decimals.map { Amounts.format(units, decimals: $0, maxFraction: $0) } ?? units
        return [shown, item.symbol].compactMap { $0 }.joined(separator: " ")
    }
}
