import Foundation

/// Aztec's fee token, shown first: every account has a Fee Juice balance (public only: it arrives bridged from
/// Ethereum) and spends it on fees. It cannot be sent, so it lives beside the followed tokens, under an id that is no
/// address.
public enum FeeJuice {
    public static let id = "fee-juice"
    public static let info = TokenInfo(address: id, name: "Fee Juice", symbol: "FJ", decimals: 18)

    /// The account's Fee Juice as a token, its balance unknown until read.
    public static func token(_ units: String?) -> TokenView {
        TokenView(info: info, balance: units.map { TokenBalance(token: id, private: "0", public: $0) })
    }

    public static func isIt(_ token: TokenView) -> Bool { token.info.address == id }
}

/// What the wallet says about fees. Test networks have a Sponsored FPC that pays them all; elsewhere (mainnet) the
/// account pays with its own Fee Juice, bridged from Ethereum as AZTEC to its address, activation included.
public enum Fees {
    public static let sponsored = "None, paid by the network's sponsor"
    private static let decimals = 18

    public static func paysWithFeeJuice(_ check: NetworkCheck?) -> Bool { check?.fees == "feeJuice" }

    /// The Fee line of a confirmation; `feeJuice` is the account's balance in base units, when known.
    public static func line(_ check: NetworkCheck?, feeJuice: String?) -> String {
        guard paysWithFeeJuice(check) else { return sponsored }
        guard let feeJuice else { return "Paid with your Fee Juice" }
        if !Amounts.isPositive(feeJuice) { return "Paid with Fee Juice, which this account does not hold: the transaction will fail" }
        return "Paid with your Fee Juice (you hold \(Amounts.display(feeJuice, decimals: decimals, maxFraction: 4)) FJ)"
    }

    /// The text of the Activate notice.
    public static func activation(_ check: NetworkCheck?, feeJuice: String?) -> String {
        if !paysWithFeeJuice(check) {
            return "Your address can already receive funds. Activating publishes your account contract; its fee is sponsored on this network."
        }
        if let feeJuice, Amounts.isPositive(feeJuice) {
            return "Your address can already receive funds. Activating publishes your account contract, paid with the Fee Juice it holds."
        }
        return "Activating costs Fee Juice, which this account does not hold yet: bridge AZTEC from Ethereum to your address, then activate."
    }
}
