package dev.azwall.core.vault

import dev.azwall.core.pxe.AccountSecrets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/** Encrypts and decrypts the vault file. [KeystoreSecretBox] on devices, a fake in tests. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/**
 * Everything secret the wallet holds: the accounts' signing keys (the account roots on Aztec) and the
 * key of the encrypted PXE stores. Never written anywhere but the vault file, sealed by [SecretBox].
 */
@Serializable
data class VaultContents(val version: Int = 1, val accounts: List<AccountSecrets>, val dbKey: String) {
    init {
        require(version == 1) { "unsupported vault version $version" }
        require(DB_KEY.matches(dbKey)) { "malformed store key" }
        accounts.forEach { require(KEY.matches(it.signingKey) && KEY.matches(it.salt)) { "malformed account ${it.alias}" } }
    }

    companion object {
        private val KEY = Regex("^0x[0-9a-f]{64}$")
        private val DB_KEY = Regex("^0x[0-9a-f]{64}$")
    }
}

class Vault(private val file: File, private val box: SecretBox, private val random: SecureRandom = SecureRandom()) {
    private val json = Json { ignoreUnknownKeys = false }

    fun exists(): Boolean = file.exists()

    /** Creates the vault with its first account and a fresh store key. Fails if a vault exists. */
    fun create(first: AccountSecrets): VaultContents {
        check(!exists()) { "a vault already exists" }
        val contents = VaultContents(accounts = listOf(first), dbKey = "0x" + hex(ByteArray(32).also(random::nextBytes)))
        write(contents)
        return contents
    }

    /** Decrypts the vault. May throw an authentication-required error from the [SecretBox]. */
    fun unlock(): VaultContents = json.decodeFromString(VaultContents.serializer(), box.open(file.readBytes()).decodeToString())

    fun addAccount(contents: VaultContents, account: AccountSecrets): VaultContents {
        require(contents.accounts.none { it.signingKey == account.signingKey }) { "this account is already in the wallet" }
        require(contents.accounts.none { it.alias == account.alias }) { "an account is already named ${account.alias}" }
        return contents.copy(accounts = contents.accounts + account).also(::write)
    }

    fun renameAccount(contents: VaultContents, signingKey: String, alias: String): VaultContents {
        require(alias.isNotBlank()) { "the name is empty" }
        return contents.copy(accounts = contents.accounts.map { if (it.signingKey == signingKey) it.copy(alias = alias.trim()) else it })
            .also(::write)
    }

    /** Deletes the vault file. The keys are gone unless the user backed them up. */
    fun wipe() {
        file.delete()
    }

    private fun write(contents: VaultContents) {
        val sealed = box.seal(json.encodeToString(VaultContents.serializer(), contents).encodeToByteArray())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealed)
        check(tmp.renameTo(file)) { "could not write the vault" }
    }

    companion object {
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** Salt 0: the address is then a function of the signing key alone, so restoring needs only the key. */
        const val ZERO_SALT = "0x0000000000000000000000000000000000000000000000000000000000000000"

        /** Normalises a signing key typed or pasted by the user; throws on anything but 32 bytes of hex. */
        fun parseSigningKey(input: String): String {
            val body = input.trim().removePrefix("0x").lowercase()
            require(body.length == 64 && body.all { it in "0123456789abcdef" }) { "a signing key is 64 hexadecimal characters" }
            return "0x$body"
        }
    }
}
