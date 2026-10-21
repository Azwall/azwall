import Foundation

/// How accounts come to be: from the wallet's recovery phrase (account 0, 1, 2… in order, so restoring the phrase and
/// adding accounts again brings them back), or from a signing key (older backups, other wallets).
public enum Recovery {
    /// True when the user gave a signing key rather than words.
    public static func isSigningKey(_ secret: String) -> Bool {
        var body = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        if body.hasPrefix("0x") { body.removeFirst(2) }
        return body.count == 64 && body.allSatisfy(Vault.isAsciiHex)
    }

    /// The first account of a new wallet and the phrase to keep with it (nil when started from a key).
    public static func firstAccount(_ secret: String, alias: String, backend: WalletBackend) throws -> (AccountSecrets, String?) {
        let trimmed = alias.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = trimmed.isEmpty ? "Account 1" : trimmed
        if isSigningKey(secret) { return (AccountSecrets(alias: name, signingKey: try Vault.parseSigningKey(secret), salt: Vault.zeroSalt), nil) }
        let phrase = try backend.normalizeRecoveryPhrase(secret)
        return (AccountSecrets(alias: name, signingKey: try backend.accountSigningKey(phrase: phrase, index: 0), salt: Vault.zeroSalt, index: 0), phrase)
    }

    /// A new account: imported from `importedKey`, else the phrase's next one; wallets created from a key before
    /// recovery phrases existed get a random key, backed up on its own.
    public static func nextAccount(_ contents: VaultContents, alias: String, importedKey: String?, backend: WalletBackend) throws -> AccountSecrets {
        let trimmed = alias.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = trimmed.isEmpty ? "Account \(contents.accounts.count + 1)" : trimmed
        if let importedKey { return AccountSecrets(alias: name, signingKey: try Vault.parseSigningKey(importedKey), salt: Vault.zeroSalt) }
        guard let phrase = contents.phrase else { return AccountSecrets(alias: name, signingKey: Vault.newSigningKey(), salt: Vault.zeroSalt) }
        let index = contents.nextIndex
        return AccountSecrets(alias: name, signingKey: try backend.accountSigningKey(phrase: phrase, index: index), salt: Vault.zeroSalt, index: index)
    }
}
