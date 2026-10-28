import Foundation

/// Token amounts: base units as decimal strings on the wire (they exceed 64 bits), human strings on screen.
/// Swift has no big integer type, so the arithmetic the UI needs (format, parse, add, compare) works on digits.
public enum Amounts {
    public struct InvalidAmount: Error, Equatable, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// `"1234500000", 6` → `"1,234.5"`. At most `maxFraction` decimals, rounded down; trailing zeros dropped.
    public static func format(_ units: String, decimals: Int, maxFraction: Int = 6) -> String {
        let digits = normalized(units)
        let padded = String(repeating: "0", count: max(0, decimals + 1 - digits.count)) + digits
        let intPart = String(padded.dropLast(decimals))
        var fraction = String(padded.suffix(decimals).prefix(min(decimals, maxFraction)))
        while fraction.hasSuffix("0") { fraction.removeLast() }
        let grouped = group(intPart)
        return fraction.isEmpty ? grouped : "\(grouped).\(fraction)"
    }

    /// `format` for balances on screen: an amount above zero that would show as `0` (dust) shows as `< 0.01`, so a
    /// balance never looks empty when it is not.
    public static func display(_ units: String, decimals: Int, maxFraction: Int = 6) -> String {
        let text = format(units, decimals: decimals, maxFraction: maxFraction)
        guard isPositive(units), text == "0" else { return text }
        let shown = min(decimals, maxFraction)
        return shown == 0 ? "< 1" : "< 0.\(String(repeating: "0", count: shown - 1))1"
    }

    /// `"1.5", 6` → `"1500000"`. Accepts `.` or `,`; rejects more decimals than the token has, and zero.
    public static func parse(_ input: String, decimals: Int) throws -> String {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        // "1,000" is a thousand in English and one in French: refused rather than read the cheaper way.
        if trimmed.range(of: #"^0*[1-9][0-9]*,[0-9]{3}$"#, options: .regularExpression) != nil {
            throw InvalidAmount(message: "write it with a dot for decimals, and no thousands separator")
        }
        let text = trimmed.replacingOccurrences(of: ",", with: ".")
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard !text.isEmpty, parts.count <= 2, parts.allSatisfy({ $0.allSatisfy(\.isASCIIDigit) }), parts.contains(where: { !$0.isEmpty }) else {
            throw InvalidAmount(message: "enter a number")
        }
        let fraction = parts.count == 2 ? String(parts[1]) : ""
        guard fraction.count <= decimals else { throw InvalidAmount(message: "at most \(decimals) decimals") }
        let units = normalized(String(parts[0]) + fraction + String(repeating: "0", count: decimals - fraction.count))
        guard isPositive(units) else { throw InvalidAmount(message: "enter an amount above zero") }
        return units
    }

    public static func isPositive(_ units: String) -> Bool {
        units.allSatisfy(\.isASCIIDigit) && units.contains { $0 != "0" }
    }

    /// Compares two non-negative decimal integers.
    public static func compare(_ a: String, _ b: String) -> ComparisonResult {
        let x = normalized(a), y = normalized(b)
        if x.count != y.count { return x.count < y.count ? .orderedAscending : .orderedDescending }
        return x == y ? .orderedSame : (x < y ? .orderedAscending : .orderedDescending)
    }

    /// Sum of two non-negative decimal integers.
    public static func add(_ a: String, _ b: String) -> String {
        var result: [Character] = []
        var carry = 0
        var x = Array(normalized(a)), y = Array(normalized(b))
        while !x.isEmpty || !y.isEmpty || carry > 0 {
            let sum = (x.popLast()?.wholeNumberValue ?? 0) + (y.popLast()?.wholeNumberValue ?? 0) + carry
            result.append(Character(String(sum % 10)))
            carry = sum / 10
        }
        return normalized(String(result.reversed()))
    }

    private static func normalized(_ s: String) -> String {
        let trimmed = s.drop { $0 == "0" }
        return trimmed.isEmpty ? "0" : String(trimmed)
    }

    private static func group(_ digits: String) -> String {
        var out = ""
        for (i, c) in digits.reversed().enumerated() {
            if i > 0 && i % 3 == 0 { out.append(",") }
            out.append(c)
        }
        return String(out.reversed())
    }
}

private extension Character {
    var isASCIIDigit: Bool { isASCII && isNumber }
}
