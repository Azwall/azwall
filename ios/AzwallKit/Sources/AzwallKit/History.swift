import Foundation

/// Turns the private transfers the PXE reads back from the chain into activity items, for the ones the history
/// does not have: transfers made before the wallet recorded dApp transactions, or from another wallet with the
/// same keys. A transaction the wallet already recorded (same hash) keeps its own, richer item.
enum History {
    static func items(owner: String, moves: [TokenMove], tokens: [TokenInfo], known: [ActivityItem]) -> [ActivityItem] {
        let recorded = Set(known.compactMap { $0.txHash?.lowercased() })
        return moves.filter { !recorded.contains($0.txHash.lowercased()) }.compactMap { move in
            guard let token = tokens.first(where: { $0.address.lowercased() == move.token.lowercased() }) else { return nil }
            return item(owner: owner, move: move, token: token)
        }
    }

    private static func isZero(_ address: String) -> Bool {
        address.hasPrefix("0x") && address.dropFirst(2).allSatisfy { $0 == "0" }
    }

    private static func item(owner: String, move: TokenMove, token: TokenInfo) -> ActivityItem? {
        let fromMe = move.from.lowercased() == owner.lowercased()
        let toMe = move.to.lowercased() == owner.lowercased()
        let kind: ActivityKind
        if fromMe && toMe { return nil } // change returned to the sender: not a movement
        else if fromMe { kind = .sendPrivate }
        else if isZero(move.from) { kind = .mint }
        else { kind = .receive }
        var item = ActivityItem(
            id: "chain-\(move.txHash)-\(move.token)-\(move.to)-\(move.amount)", account: owner, kind: kind, status: .confirmed,
            createdAt: Date(timeIntervalSince1970: Double(move.at) / 1000),
        )
        item.txHash = move.txHash
        item.token = token.address
        item.symbol = token.symbol
        item.decimals = token.decimals
        item.amount = move.amount
        let other = fromMe ? move.to : move.from
        item.counterparty = isZero(other) ? nil : other
        item.blockNumber = move.block
        return item
    }
}
