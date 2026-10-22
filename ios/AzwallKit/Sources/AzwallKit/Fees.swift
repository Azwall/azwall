import Foundation

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
