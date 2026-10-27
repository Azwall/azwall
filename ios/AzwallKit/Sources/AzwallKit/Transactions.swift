import Foundation

/// Every transaction follows the same path, recorded in the history: proving (witnesses and the Chonk proof are
/// made on the device), pending (accepted by the node), then confirmed or failed. Proving is the long part
/// (15 to 30 s on a phone); mining is awaited in the background.
extension WalletSession {
    /// Deploys the selected account's contract, once: a second activation would only pay a fee to fail. With Fee Juice
    /// bridged and ready to claim, the activation claims it and pays its fee with it, in one transaction. Refused when
    /// nothing can pay it (Fees.canActivate): it would only fail once proven.
    public func deployAccount() async throws {
        guard !activating else { throw PxeError("the account's activation is already on its way") }
        let claim = claimable
        guard Fees.canActivate(check, feeJuice: feeJuice, claim: claim) else {
            throw PxeError("this account holds no Fee Juice to pay its activation: bridge AZTEC from Ethereum to its address first")
        }
        let me = try selectedAddress()
        let secrets = try secrets(of: me)
        try await track(ActivityItem(account: me, kind: .deployAccount)) { client in try await client.deployAccount(secrets, claim: claim) }
    }

    /// Claims Fee Juice bridged to the selected, active account; the transaction pays its fee with what it claims.
    public func claimFeeJuice(_ deposit: FeeJuiceDeposit) async throws {
        guard account?.deployed == true else { throw PxeError("activate the account first: activation claims it too") }
        let me = try selectedAddress()
        var item = ActivityItem(account: me, kind: .claimFeeJuice)
        item.token = FeeJuice.id
        item.symbol = FeeJuice.info.symbol
        item.decimals = FeeJuice.info.decimals
        item.amount = deposit.amount
        try await track(item) { client in try await client.claimFeeJuice(owner: me, deposit: deposit) }
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
            store.markCreated(account: admin, token: deployed.token)
            return deployed.txHash
        }
    }

    /// Test networks: mints to `to` (the selected account when nil or empty), as the token's minter.
    public func mint(token: TokenInfo, amount: String, toPrivate: Bool, to: String? = nil) async throws {
        let me = try selectedAddress()
        let recipient = to?.trimmingCharacters(in: .whitespaces).nilIfEmpty ?? me
        var item = ActivityItem(account: me, kind: .mint)
        item.token = token.address
        item.symbol = token.symbol
        item.decimals = token.decimals
        item.amount = amount
        item.counterparty = recipient == me ? nil : recipient
        try await track(item) { client in
            try await client.mint(admin: me, token: token.address, to: recipient, amount: amount, toPrivate: toPrivate)
        }
    }

    private func selectedAddress() throws -> String {
        guard let address = account?.address else { throw PxeError("no account selected") }
        return address
    }

    /// Cancels the transaction being proven: the screen is released at once, and its proof is refused (ProofGate).
    /// Returns once no cancelled transaction is still being proven: a dApp transaction approved now waits for it, so
    /// the cancel that refuses that proof (ProofGate) is not taken for the dApp's.
    public func settle() async {
        await winding?.value
    }

    public func cancelProving() {
        gate.cancel()
        stopProving?()
    }

    /// Proves and sends with `send` (returns the tx hash), then follows the transaction to a block.
    private func track(_ draft: ActivityItem, _ send: @escaping (PxeClient) async throws -> String) async throws {
        guard proving == nil else { throw PxeError("another transaction is being proven") }
        // One cancel gate for every proof (ProofGate): a cancel must never land on a dApp's transaction.
        guard !activity.contains(where: { $0.kind == .dapp && $0.status == .proving }) else {
            throw PxeError("a dApp transaction is being proven; try again once it is sent")
        }
        let client = try self.client
        record(draft)
        setProving(draft)
        defer { setProving(nil); stopProving = nil }
        var sent = draft
        do {
            sent.txHash = try await proveAndSend(draft, client, send)
            sent.status = .pending
        } catch {
            sent.status = .failed
            sent.error = error is CancelledByUser ? "Cancelled" : error.localizedDescription
            record(sent)
            throw error
        }
        record(sent)
        Task { await confirm(sent, client) }
    }

    /// Runs `send`, or gives it up as soon as the user cancels. Witnesses and the proof cannot be stopped, so a
    /// cancelled `send` goes on in the background until the app's prove handler refuses its proof.
    private func proveAndSend(_ draft: ActivityItem, _ client: PxeClient, _ send: @escaping (PxeClient) async throws -> String) async throws -> String {
        let first = FirstOf<String?>()
        stopProving = { first.finish(.success(nil)) }
        await winding?.value
        winding = nil
        if first.finished { gate.reset(); throw CancelledByUser() }
        gate.reset()
        let work = Task { try await send(client) }
        Task { first.finish(await work.result.map { Optional($0) }) }
        do {
            if let hash = try await first.value() { return hash }
        } catch {
            let cancelled = gate.cancelled || error is CancelledByUser
            gate.reset()
            throw cancelled ? CancelledByUser() : error
        }
        winding = Task { await windDown(draft, work, client) }
        throw CancelledByUser()
    }

    /// Waits for a cancelled transaction to end; one whose proof was accepted just before the cancel was sent, so
    /// it is followed.
    private func windDown(_ draft: ActivityItem, _ work: Task<String, Error>, _ client: PxeClient) async {
        let hash = try? await work.value
        gate.reset()
        guard let hash else { return }
        var sent = draft
        sent.txHash = hash
        sent.status = .pending
        record(sent)
        await confirm(sent, client)
    }

    /// Follows a sent transaction to a block. A wait can end without the transaction failing (the app was
    /// suspended, the host stopped, a timeout), so the outcome always comes from a receipt; with none, the item
    /// stays as it is and `resume()` looks again on the next opening.
    func confirm(_ sent: ActivityItem, _ client: PxeClient) async {
        guard let hash = sent.txHash else { return }
        let receipt: Receipt?
        do {
            receipt = try await client.waitForTx(hash)
        } catch is CancellationError {
            return
        } catch {
            receipt = try? await client.txReceipt(hash)
        }
        guard let receipt, let done = Settlement.settle(sent, receipt) else { return }
        record(done)
        await refresh()
    }

    /// Looks again at transactions sent but not settled by a receipt (e.g. the app closed while waiting).
    func resume(_ client: PxeClient) {
        for item in store.unsettled() { Task { await confirm(item, client) } }
    }
}

/// How a receipt settles a sent transaction.
public enum Settlement {
    /// Failures read from a receipt: final, never looked at again.
    public static let reverted = "reverted on chain"
    public static let dropped = "dropped by the network"

    /// The item once `receipt` is known, or nil while the transaction is still pending.
    public static func settle(_ item: ActivityItem, _ receipt: Receipt) -> ActivityItem? {
        var done = item
        switch receipt.settled {
        case .pending: return nil
        case .success:
            done.status = .confirmed
            done.blockNumber = receipt.blockNumber
            done.error = nil
        case .reverted:
            done.status = .failed
            done.blockNumber = receipt.blockNumber
            done.error = reverted
        case .dropped:
            done.status = .failed
            done.error = dropped
        }
        return done
    }
}

extension String {
    /// nil for an empty string: optional chaining over user input.
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
