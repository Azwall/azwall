import Foundation

/// What a scanned QR code (or a pasted link) asks for. Either a bare Aztec address, or a payment request URI in the
/// spirit of Ethereum's EIP-681 and Zcash's ZIP-321:
///
///     aztec:<address>?network=testnet-v6&token=<token address>&amount=1.5
///
/// Every parameter is optional; `amount` is in the token's units (not base units). Addresses differ between Aztec
/// versions, hence `network`: the wallet warns when it is not the open one.
public struct PaymentRequest: Equatable, Sendable {
    public let address: String
    public var network: String?
    public var token: String?
    public var amount: String?

    public init(address: String, network: String? = nil, token: String? = nil, amount: String? = nil) {
        self.address = address
        self.network = network
        self.token = token
        self.amount = amount
    }

    public static func isAddress(_ text: String) -> Bool {
        text.count == 66 && text.hasPrefix("0x") && text.dropFirst(2).allSatisfy(\.isHexDigit)
    }

    /// The request in `text`, or nil when it is neither an address nor an `aztec:` URI with one.
    public static func parse(_ text: String) -> PaymentRequest? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if isAddress(trimmed) { return PaymentRequest(address: trimmed) }
        guard trimmed.lowercased().hasPrefix("aztec:") else { return nil }
        var rest = String(trimmed.dropFirst("aztec:".count))
        if rest.hasPrefix("//") { rest.removeFirst(2) }
        let parts = rest.split(separator: "?", maxSplits: 1).map(String.init)
        guard let address = parts.first, isAddress(address) else { return nil }
        var params: [String: String] = [:]
        for pair in (parts.count > 1 ? parts[1] : "").split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2 { params[kv[0].lowercased()] = kv[1].removingPercentEncoding ?? kv[1] }
        }
        let amount = params["amount"].flatMap { $0.range(of: #"^\d+(\.\d+)?$"#, options: .regularExpression) != nil ? $0 : nil }
        let network = params["network"].flatMap { $0.isEmpty ? nil : $0 }
        return PaymentRequest(address: address, network: network, token: params["token"].flatMap { isAddress($0) ? $0 : nil }, amount: amount)
    }

    /// The URI form: what a Receive screen encodes in its QR code when it asks for something specific.
    public var uri: String {
        let params = [network.map { "network=\($0)" }, token.map { "token=\($0)" }, amount.map { "amount=\($0)" }].compactMap { $0 }
        return params.isEmpty ? address : "aztec:\(address)?\(params.joined(separator: "&"))"
    }
}
