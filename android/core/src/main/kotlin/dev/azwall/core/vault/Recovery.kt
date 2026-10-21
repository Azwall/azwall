package dev.azwall.core.vault

import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.AccountSecrets

/**
 * How accounts come to be: from the wallet's recovery phrase (account 0, 1, 2… in order, so restoring the phrase
 * and adding accounts again brings them back), or from a signing key (older backups, other wallets).
 */
object Recovery {
    private val HEX_KEY = Regex("^(0x)?[0-9a-fA-F]{64}$")

    /** True when the user gave a signing key rather than words. */
    fun isSigningKey(secret: String): Boolean = HEX_KEY.matches(secret.trim())

    /** The first account of a new wallet and the phrase to keep with it (null when started from a key). */
    fun firstAccount(secret: String, alias: String, backend: WalletBackend): Pair<AccountSecrets, String?> {
        val name = alias.trim().ifEmpty { "Account 1" }
        if (isSigningKey(secret)) return AccountSecrets(name, Vault.parseSigningKey(secret), Vault.ZERO_SALT) to null
        val phrase = backend.normalizeRecoveryPhrase(secret)
        return AccountSecrets(name, backend.accountSigningKey(phrase, 0), Vault.ZERO_SALT, index = 0) to phrase
    }

    /**
     * A new account: imported from [importedKey], else the phrase's next one; wallets created from a key before
     * recovery phrases existed get a random key, backed up on its own.
     */
    fun nextAccount(contents: VaultContents, alias: String, importedKey: String?, backend: WalletBackend): AccountSecrets {
        val name = alias.trim().ifEmpty { "Account ${contents.accounts.size + 1}" }
        importedKey?.let { return AccountSecrets(name, Vault.parseSigningKey(it), Vault.ZERO_SALT) }
        val phrase = contents.phrase ?: return AccountSecrets(name, Vault.newSigningKey(), Vault.ZERO_SALT)
        val index = contents.nextIndex
        return AccountSecrets(name, backend.accountSigningKey(phrase, index), Vault.ZERO_SALT, index = index)
    }
}
