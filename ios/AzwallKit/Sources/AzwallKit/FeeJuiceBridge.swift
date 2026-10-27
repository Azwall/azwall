import Foundation

/// AZTEC deposited for an account on the network's Fee Juice portal, on Ethereum: it becomes Fee Juice once claimed
/// (pxe/host/src/fee_juice.ts). `ours` when the wallet knows its claim secret, `ready` once its message is on Aztec.
public struct FeeJuiceDeposit: Codable, Equatable, Sendable {
    /// Base units, as a decimal string.
    public let amount: String
    public let messageHash: String
    public let leafIndex: String
    public var l1Tx: String?
    public let ours: Bool
    public let ready: Bool
    public let claimed: Bool
    /// The claim secret, hex, when the wallet knows it (`ours`).
    public var secret: String?

    public init(amount: String, messageHash: String, leafIndex: String, ours: Bool, ready: Bool, claimed: Bool, secret: String? = nil) {
        self.amount = amount
        self.messageHash = messageHash
        self.leafIndex = leafIndex
        self.ours = ours
        self.ready = ready
        self.claimed = claimed
        self.secret = secret
    }

    /// The wallet can claim it now.
    public var claimable: Bool { ours && ready && !claimed }

    /// Deposited, its message not on Aztec yet.
    public var arriving: Bool { ours && !ready && !claimed }

    /// Deposited with a secret the wallet does not know: the user can enter it.
    public var locked: Bool { !ours }
}

/// Claim secrets of Fee Juice bridge deposits, as the user types or pastes them.
public enum ClaimSecrets {
    /// The BN254 scalar field, in hex: a secret is an element of it.
    private static let modulus = "30644e72e131a029b85045b68181585d2833e84879b9709143e1f593f0000001"

    /// `0x…` hex or decimal, spaces and a trailing newline ignored, as a 0x-prefixed 64-digit hex field element.
    public static func normalize(_ text: String) throws -> String {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: " ", with: "")
        let hex: String? = t.lowercased().hasPrefix("0x") ? hexDigits(String(t.dropFirst(2))) : decimalToHex(t)
        guard let hex else { throw PxeError("not a claim secret: enter it in hex (0x…) or as a number") }
        let trimmed = String(hex.drop { $0 == "0" })
        let padded = String(repeating: "0", count: max(0, 64 - trimmed.count)) + trimmed
        // Equal lengths: comparing the digits as text compares the numbers.
        guard padded.count == 64, padded < modulus else { throw PxeError("not a claim secret: enter it in hex (0x…) or as a number") }
        return "0x" + padded
    }

    private static func hexDigits(_ s: String) -> String? {
        let lower = s.lowercased()
        return !lower.isEmpty && lower.allSatisfy(\.isHexDigit) ? lower : nil
    }

    /// A decimal number of any size in hex, by long division by 16.
    private static func decimalToHex(_ s: String) -> String? {
        guard !s.isEmpty, s.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        var digits = s.compactMap(\.wholeNumberValue)
        var out = ""
        while !(digits.allSatisfy { $0 == 0 }) {
            var rest = 0, next: [Int] = []
            for d in digits {
                let n = rest * 10 + d
                if !next.isEmpty || n / 16 > 0 { next.append(n / 16) }
                rest = n % 16
            }
            out = String(rest, radix: 16) + out
            digits = next.isEmpty ? [0] : next
        }
        return out.isEmpty ? "0" : out
    }
}

/// Fee Juice bridged to the account from Ethereum, as a line under its amount: `claim` when it can be claimed now (by
/// an active account: activation claims it otherwise), `askSecret` when it was made with a secret the wallet lacks.
public struct Bridged: Equatable, Sendable {
    public let text: String
    public let claim: Bool
    public var askSecret = false
}

extension WalletSession {
    /// What the home screen says of the bridge: ready to claim, on its way, or waiting for its secret.
    public var bridged: Bridged? {
        let d = FeeJuice.info.decimals
        if let ready = claimable {
            let amount = Amounts.display(ready.amount, decimals: d, maxFraction: 4)
            // Sent, not in a block yet: said as such, without a second Claim button.
            if claiming { return Bridged(text: "Claiming \(amount) FJ bridged from Ethereum…", claim: false) }
            return Bridged(text: "\(amount) FJ bridged from Ethereum", claim: account?.deployed == true)
        }
        if Amounts.isPositive(arriving) {
            return Bridged(text: "\(Amounts.display(arriving, decimals: d, maxFraction: 4)) FJ arriving from Ethereum", claim: false)
        }
        if let first = locked.first(where: { !$0.claimed }) {
            return Bridged(text: "\(Amounts.display(first.amount, decimals: d, maxFraction: 4)) FJ bridged with your own secret", claim: false, askSecret: true)
        }
        return nil
    }
}
