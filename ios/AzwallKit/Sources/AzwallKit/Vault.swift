import Foundation
import Security

/// Where the sealed vault lives: the Keychain (behind Face ID, Touch ID or the passcode) in the app, memory in tests.
public protocol VaultStorage: Sendable {
    func exists() -> Bool
    /// May show the system authentication prompt; throws if the user cancels.
    func read() throws -> Data
    func write(_ data: Data) throws
    func delete() throws
}

/// Everything secret the wallet holds: the accounts' signing keys and the key of the encrypted PXE stores.
public struct VaultContents: Codable, Equatable, Sendable {
    public var version: Int = 1
    public var accounts: [AccountSecrets]
    public let dbKey: String

    public init(accounts: [AccountSecrets], dbKey: String) {
        self.accounts = accounts
        self.dbKey = dbKey
    }

    func validate() throws {
        guard version == 1 else { throw VaultError("unsupported vault version \(version)") }
        guard Vault.isHex32(dbKey) else { throw VaultError("malformed store key") }
        for a in accounts where !(Vault.isHex32(a.signingKey) && Vault.isHex32(a.salt)) {
            throw VaultError("malformed account \(a.alias)")
        }
    }
}

public struct VaultError: Error, Equatable, LocalizedError {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

public struct Vault: Sendable {
    private let storage: VaultStorage

    public init(storage: VaultStorage) {
        self.storage = storage
    }

    public func exists() -> Bool { storage.exists() }

    /// Creates the vault with its first account and a fresh store key. Fails if a vault exists.
    public func create(first: AccountSecrets) throws -> VaultContents {
        guard !exists() else { throw VaultError("a vault already exists") }
        let contents = VaultContents(accounts: [first], dbKey: "0x" + Self.hex(Self.randomBytes(32)))
        try write(contents)
        return contents
    }

    public func unlock() throws -> VaultContents {
        let contents = try JSONDecoder().decode(VaultContents.self, from: try storage.read())
        try contents.validate()
        return contents
    }

    public func addAccount(_ contents: VaultContents, _ account: AccountSecrets) throws -> VaultContents {
        guard !contents.accounts.contains(where: { $0.signingKey == account.signingKey }) else { throw VaultError("this account is already in the wallet") }
        guard !contents.accounts.contains(where: { $0.alias == account.alias }) else { throw VaultError("an account is already named \(account.alias)") }
        var next = contents
        next.accounts.append(account)
        try write(next)
        return next
    }

    public func renameAccount(_ contents: VaultContents, signingKey: String, alias: String) throws -> VaultContents {
        let name = alias.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { throw VaultError("the name is empty") }
        var next = contents
        next.accounts = next.accounts.map { var a = $0; if a.signingKey == signingKey { a.alias = name }; return a }
        try write(next)
        return next
    }

    public func wipe() throws { try storage.delete() }

    private func write(_ contents: VaultContents) throws {
        try contents.validate()
        try storage.write(JSONEncoder().encode(contents))
    }

    // MARK: - Keys

    /// Salt 0: the address is then a function of the signing key alone, so restoring needs only the key.
    public static let zeroSalt = "0x" + String(repeating: "0", count: 64)

    /// Order of the Grumpkin scalar field (Aztec signing keys are elements of it), as 64 hex digits.
    static let grumpkinOrder = "30644e72e131a029b85045b68181585d97816a916871ca8d3c208c16d87cfd47"

    /// ASCII only: `Character.isHexDigit` also accepts fullwidth digits.
    static func isAsciiHex(_ c: Character) -> Bool { c.isASCII && c.isHexDigit }

    static func isHex32(_ s: String) -> Bool {
        s.count == 66 && s.hasPrefix("0x") && s.dropFirst(2).allSatisfy { isAsciiHex($0) && !$0.isUppercase }
    }

    /// Normalises a typed or pasted key; throws unless it is a non-zero Grumpkin scalar.
    public static func parseSigningKey(_ input: String) throws -> String {
        var body = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if body.hasPrefix("0x") { body.removeFirst(2) }
        guard body.count == 64, body.allSatisfy(isAsciiHex) else { throw VaultError("a signing key is 64 hexadecimal characters") }
        // Same length, lowercase hex: string order is numeric order.
        guard body != String(repeating: "0", count: 64), body < grumpkinOrder else { throw VaultError("this is not a valid Aztec signing key") }
        return "0x" + body
    }

    /// A fresh signing key, by rejection sampling of 32 random bytes below the field order (unbiased).
    public static func newSigningKey() -> String {
        while true {
            if let key = try? parseSigningKey(hex(randomBytes(32))) { return key }
        }
    }

    static func randomBytes(_ count: Int) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: count)
        guard SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess else { fatalError("no system randomness") }
        return bytes
    }

    static func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }
}
