import Foundation

/// What the confirmation screen lists before a transaction is proven and sent: every detail the user typed or chose,
/// the recipient in full, and what stays private. Nothing is proven or sent until the user confirms it.
public struct Review: Equatable, Sendable {
    public struct Line: Equatable, Sendable, Identifiable {
        public let label: String
        public let value: String
        public var id: String { label }
    }

    public let title: String
    public let lines: [Line]
    public let confirm: String

    public func value(_ label: String) -> String? { lines.first { $0.label == label }?.value }

    /// A transfer of `amount` (already formatted, with its symbol); `to` is ignored when shielding. `fee` is the Fee
    /// line (Fees.line).
    public static func transfer(_ kind: TransferKind, amount: String, to: String, network: String, fee: String = Fees.sponsored) -> Review {
        func make(_ title: String, _ middle: [(String, String)], _ confirm: String) -> Review {
            let all = [("Amount", amount)] + middle + [("Network", network), ("Fee", fee)]
            return Review(title: title, lines: all.map { Line(label: $0.0, value: $0.1) }, confirm: confirm)
        }
        switch kind {
        case .private: return make("Send privately", [("To", to), ("Privacy", "Amount, sender and recipient stay hidden")], "Confirm and send")
        case .public: return make("Send publicly", [("To", to), ("Privacy", "Visible to everyone on chain")], "Confirm and send")
        case .shield: return make("Make private", [("Moves", "Public balance → private balance"), ("Privacy", "The amount is visible once, then hidden")], "Confirm")
        case .unshield: return make("Make public", [("Moves", "Private balance → public balance"), ("Privacy", "The amount becomes visible to everyone")], "Confirm")
        }
    }

    /// Activating the account: it publishes its contract, once, and pays `fee` where there is no sponsor.
    public static func activation(network: String, fee: String = Fees.sponsored) -> Review {
        let lines = [("Publishes", "Your account contract, so the account can send transactions"), ("Network", network), ("Fee", fee)]
        return Review(title: "Activate your account", lines: lines.map { Line(label: $0.0, value: $0.1) }, confirm: "Confirm and activate")
    }

    /// Claiming Fee Juice bridged from Ethereum: the transaction pays its fee out of what it claims.
    public static func claimFeeJuice(amount: String, network: String) -> Review {
        let lines = [
            ("Amount", amount),
            ("From", "Your deposit on the Fee Juice portal, on Ethereum"),
            ("Network", network),
            ("Fee", "Paid out of the Fee Juice this transaction claims"),
        ]
        return Review(title: "Claim your Fee Juice", lines: lines.map { Line(label: $0.0, value: $0.1) }, confirm: "Confirm and claim")
    }

    /// Minting `amount` of the account's own token; an empty `to` mints to the account itself.
    public static func mint(amount: String, toPrivate: Bool, to: String, network: String, fee: String = Fees.sponsored) -> Review {
        let lines = [
            ("Amount", amount),
            ("To", to.isEmpty ? "This account" : to),
            ("Privacy", toPrivate ? "A note only the recipient can see" : "Visible to everyone on chain"),
            ("Network", network),
            ("Fee", fee),
        ]
        return Review(title: toPrivate ? "Mint privately" : "Mint publicly", lines: lines.map { Line(label: $0.0, value: $0.1) }, confirm: "Confirm and mint")
    }
}
