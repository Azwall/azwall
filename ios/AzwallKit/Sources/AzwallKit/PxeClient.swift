import Foundation

/// Sends one call to the PXE host: a `PxeChannel` in the app, a fake in tests.
public protocol PxeCaller: Sendable {
    func call(_ method: String, _ params: JSONValue) async throws -> JSONValue
}

extension PxeChannel: PxeCaller {}

/// Account material as the vault holds it (hex strings).
public struct AccountSecrets: Codable, Equatable, Sendable {
    public var alias: String
    public let signingKey: String
    public let salt: String
    /// The account's index in the wallet's recovery phrase; nil for an account imported from its key.
    public var index: Int?

    public init(alias: String, signingKey: String, salt: String, index: Int? = nil) {
        self.alias = alias
        self.signingKey = signingKey
        self.salt = salt
        self.index = index
    }
}

public struct NetworkCheck: Codable, Equatable, Sendable {
    public let nodeVersion: String
    public let l1ChainId: Int64
    public let rollupVersion: Int64
    public let vkTreeRoot: String
    public let protocolContractsHash: String
    public let blockNumber: Int64
    /// "sponsored" (the network's Sponsored FPC pays) or "feeJuice" (the account pays with its own Fee Juice); older
    /// hosts send none: sponsored.
    public var fees: String?
}

/// The node's statuses folded into what the app shows (pxe/<bundle>/src/wallet.ts `outcomeOf`).
public enum TxOutcome: String, Codable, Sendable {
    case pending, success, reverted, dropped
}

public struct Receipt: Codable, Equatable, Sendable {
    public let txHash: String
    public let status: String
    public let blockNumber: Int64?
    /// Absent from hosts older than the outcome field: a receipt from a completed wait is a success.
    public let outcome: TxOutcome?

    public var settled: TxOutcome { outcome ?? .success }
}

/// Where the PXE stands: its last synced block (number, time in seconds) and the chain's tip.
public struct SyncStatus: Codable, Equatable, Sendable {
    public let syncedBlock: Int64
    public let syncedAt: Int64
    public let tip: Int64

    public init(syncedBlock: Int64, syncedAt: Int64, tip: Int64) {
        self.syncedBlock = syncedBlock
        self.syncedAt = syncedAt
        self.tip = tip
    }

    public var blocksBehind: Int64 { max(0, tip - syncedBlock) }
}

/// A token the account holds that the wallet does not follow yet (pxe/<bundle>/src/discover.ts).
public struct FoundToken: Codable, Equatable, Identifiable, Sendable {
    public let address: String
    public let name: String
    public let symbol: String
    public let decimals: Int
    public let `private`: String
    public let `public`: String

    public var id: String { address }
    public var info: TokenInfo { TokenInfo(address: address, name: name, symbol: symbol, decimals: decimals) }
    public var balance: TokenBalance { TokenBalance(token: address, private: self.private, public: self.public) }
}

/// One private token transfer of the account, read back from the chain (pxe/<bundle>/src/history.ts).
public struct TokenMove: Codable, Equatable, Sendable {
    public let token: String
    public let txHash: String
    public let block: Int64
    /// Block time, in milliseconds (0 if unknown).
    public let at: Int64
    public let from: String
    public let to: String
    public let amount: String
}

public struct TokenInfo: Codable, Equatable, Hashable, Sendable {
    public let address: String
    public let name: String
    public let symbol: String
    public let decimals: Int

    public init(address: String, name: String, symbol: String, decimals: Int) {
        self.address = address
        self.name = name
        self.symbol = symbol
        self.decimals = decimals
    }
}

/// Balances in base units, as decimal strings (they exceed 64 bits).
public struct TokenBalance: Codable, Equatable, Sendable {
    public let token: String
    public let `private`: String
    public let `public`: String

    public init(token: String, private priv: String, public pub: String) {
        self.token = token
        self.private = priv
        self.public = pub
    }
}

public struct DeployedToken: Codable, Equatable, Sendable {
    public let token: String
    public let txHash: String
}

/// private: notes to notes · public: public to public · shield: public to own private · unshield: the reverse.
public enum TransferKind: String, Codable, Sendable, CaseIterable {
    case `private`, `public`, shield, unshield
}

/// Typed API of a PXE host (pxe/<bundle>/src/api.ts). Every call may take seconds; proving calls, tens.
public struct PxeClient: Sendable {
    private let caller: PxeCaller

    public init(caller: PxeCaller) {
        self.caller = caller
    }

    public func open(networkParamsJSON: String, dbKey: String) async throws -> NetworkCheck {
        try await typed("open", .object(["network": try JSONValue.parse(networkParamsJSON), "dbKey": .string(dbKey)]))
    }

    public func close() async throws {
        _ = try await caller.call("close", .object([:]))
    }

    /// Writes the PXE's pending store changes to disk now.
    public func flush() async throws {
        _ = try await caller.call("flush", .object([:]))
    }

    public func addAccount(_ account: AccountSecrets) async throws -> String {
        try await field("addAccount", try JSONValue.from(account), "address")
    }

    public func isDeployed(_ address: String) async throws -> Bool {
        try await caller.call("accountState", .object(["address": .string(address)]))["deployed"]?.boolValue == true
    }

    /// Proves and sends the account deployment; returns the transaction hash.
    public func deployAccount(_ account: AccountSecrets) async throws -> String {
        try await field("deployAccount", try JSONValue.from(account), "txHash")
    }

    public func waitForTx(_ txHash: String) async throws -> Receipt {
        try await typed("waitForTx", .object(["txHash": .string(txHash)]))
    }

    /// The receipt as it stands, without waiting.
    public func txReceipt(_ txHash: String) async throws -> Receipt {
        try await typed("txReceipt", .object(["txHash": .string(txHash)]))
    }

    public func syncStatus() async throws -> SyncStatus {
        try await typed("syncStatus", .object([:]))
    }

    /// Base units (18 decimals) of the Fee Juice `owner` holds.
    public func feeJuiceBalance(_ owner: String) async throws -> String {
        guard let balance = try await caller.call("feeJuiceBalance", .object(["owner": .string(owner)]))["balance"]?.stringValue else {
            throw PxeError("malformed feeJuiceBalance reply")
        }
        return balance
    }

    /// Registers followed tokens in the PXE again (its store can start over after a crash).
    public func registerTokens(_ tokens: [String]) async throws {
        _ = try await caller.call("registerTokens", .object(["tokens": .array(tokens.map { .string($0) })]))
    }

    /// Tokens registered in the PXE (by dApps too) outside `known`, that `owner` holds.
    public func discoverTokens(owner: String, known: [String]) async throws -> [FoundToken] {
        try await typed("discoverTokens", .object(["owner": .string(owner), "known": .array(known.map { .string($0) })]))
    }

    /// The private transfers of `owner` on `tokens`, as the PXE decrypts them from the chain.
    public func tokenHistory(owner: String, tokens: [String]) async throws -> [TokenMove] {
        try await typed("tokenHistory", .object(["owner": .string(owner), "tokens": .array(tokens.map { .string($0) })]))
    }

    /// An untyped call, for the dApp gateway's messages.
    public func callRaw(_ method: String, _ params: JSONValue) async throws -> JSONValue {
        try await caller.call(method, params)
    }

    public func tokenInfo(_ token: String, from: String) async throws -> TokenInfo {
        try await typed("tokenInfo", .object(["token": .string(token), "from": .string(from)]))
    }

    public func balances(owner: String, tokens: [String]) async throws -> [TokenBalance] {
        try await typed("balances", .object(["owner": .string(owner), "tokens": .array(tokens.map { .string($0) })]))
    }

    /// `amount` in base units, as a decimal string.
    public func transfer(_ kind: TransferKind, from: String, to: String, token: String, amount: String) async throws -> String {
        guard Amounts.isPositive(amount) else { throw PxeError("amount must be positive") }
        let params: [String: JSONValue] = [
            "kind": .string(kind.rawValue), "from": .string(from), "to": .string(to), "token": .string(token), "amount": .string(amount),
        ]
        return try await field("transfer", .object(params), "txHash")
    }

    public func deployToken(admin: String, name: String, symbol: String, decimals: Int) async throws -> DeployedToken {
        let params: [String: JSONValue] = ["admin": .string(admin), "name": .string(name), "symbol": .string(symbol), "decimals": .number(Double(decimals))]
        return try await typed("deployToken", .object(params))
    }

    public func mint(admin: String, token: String, to: String, amount: String, toPrivate: Bool) async throws -> String {
        let params: [String: JSONValue] = [
            "admin": .string(admin), "token": .string(token), "to": .string(to), "amount": .string(amount), "private": .bool(toPrivate),
        ]
        return try await field("mint", .object(params), "txHash")
    }

    private func typed<T: Decodable>(_ method: String, _ params: JSONValue) async throws -> T {
        try await caller.call(method, params).decode(T.self)
    }

    private func field(_ method: String, _ params: JSONValue, _ key: String) async throws -> String {
        guard let value = try await caller.call(method, params)[key]?.stringValue else { throw PxeError("\(method): no \(key) in the reply") }
        return value
    }
}
