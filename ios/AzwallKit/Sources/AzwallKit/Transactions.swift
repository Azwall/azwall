import Foundation

/// Every transaction follows the same path, recorded in the history: proving (witnesses and the Chonk proof are
/// made on the device), pending (accepted by the node), then confirmed or failed. Proving is the long part
/// (15 to 30 s on a phone); mining is awaited in the background.
extension WalletSession {
    /// Deploys the selected account's contract; fees are paid by the Sponsored FPC.
    public func deployAccount() async throws {
        let me = try selectedAddress()
        let secrets = try secrets(of: me)
        try await track(ActivityItem(account: me, kind: .deployAccount)) { client in try await client.deployAccount(secrets) }
    }

    /// Sends `amount` base units of `token`. For shield and unshield the recipient is the sender.
    public func send(_ kind: TransferKind, token: TokenInfo, to: String, amount: String) async throws {
        let me = try selectedAddress()
        let recipient = kind == .shield || kind == .unshield ? me : to.trimmingCharacters(in: .whitespaces)
        let activity: ActivityKind = switch kind {
        case .private: .sendPrivate
        case .public: .sendPublic
        case .shield: .shield
        case .unshield: .unshield
        }
        var item = ActivityItem(account: me, kind: activity)
        item.token = token.address
        item.symbol = token.symbol
        item.decimals = token.decimals
        item.amount = amount
        item.counterparty = recipient == me ? nil : recipient
        try await track(item) { client in
            try await client.transfer(kind, from: me, to: recipient, token: token.address, amount: amount)
        }
    }

    /// Test networks: deploys a token with the selected account as minter, and follows it.
    public func deployTestToken(name: String, symbol: String, decimals: Int = 6) async throws {
        let admin = try selectedAddress()
        var item = ActivityItem(account: admin, kind: .deployToken)
        item.symbol = symbol
        let store = store
        try await track(item) { client in
            let deployed = try await client.deployToken(admin: admin, name: name, symbol: symbol, decimals: decimals)
            store.addToken(TokenInfo(address: deployed.token, name: name, symbol: symbol, decimals: decimals))
            return deployed.txHash
        }
    }

    /// Test networks: mints to the selected account (which must be the token's minter).
    public func mint(token: TokenInfo, amount: String, toPrivate: Bool) async throws {
        let me = try selectedAddress()
        var item = ActivityItem(account: me, kind: .mint)
        item.token = token.address
        item.symbol = token.symbol
        item.decimals = token.decimals
        item.amount = amount
        try await track(item) { client in
            try await client.mint(admin: me, token: token.address, to: me, amount: amount, toPrivate: toPrivate)
        }
    }

    private func selectedAddress() throws -> String {
        guard let address = account?.address else { throw PxeError("no account selected") }
        return address
    }

    /// Proves and sends with `send` (returns the tx hash), then follows the transaction to a block.
    private func track(_ draft: ActivityItem, _ send: @escaping (PxeClient) async throws -> String) async throws {
        guard proving == nil else { throw PxeError("another transaction is being proven") }
        let client = try self.client
        record(draft)
        setProving(draft)
        defer { setProving(nil) }
        var sent = draft
        do {
            sent.txHash = try await send(client)
            sent.status = .pending
        } catch {
            sent.status = .failed
            sent.error = error.localizedDescription
            record(sent)
            throw error
        }
        record(sent)
        Task { await confirm(sent, client) }
    }

    private func confirm(_ sent: ActivityItem, _ client: PxeClient) async {
        var done = sent
        do {
            done.blockNumber = try await client.waitForTx(sent.txHash ?? "").blockNumber
            done.status = .confirmed
        } catch {
            done.status = .failed
            done.error = error.localizedDescription
        }
        record(done)
        await refresh()
    }
}
