package dev.azwall.core.vault

import dev.azwall.core.pxe.AccountSecrets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/** Encrypts and decrypts the vault file. [UpgradingSecretBox] on devices, a fake in tests. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray

    /** Whether [sealed] is under a key the box replaces: the vault is sealed again with its own ([Vault.upgrade]). */
    fun isLegacy(sealed: ByteArray): Boolean = false

    /** Forgets the replaced key, once a vault sealed with the new one has been opened. */
    fun dropLegacy() {}
}

/**
 * Everything secret the wallet holds: the recovery phrase (null for wallets created from a key), the accounts'
 * signing keys (the account roots on Aztec) and the key of the encrypted PXE stores. Never written anywhere but
 * the vault file, sealed by [SecretBox].
 */
@Serializable
data class VaultContents(val version: Int = 1, val accounts: List<AccountSecrets>, val dbKey: String, val phrase: String? = null) {
    init {
        require(version == 1) { "unsupported vault version $version" }
        require(KEY.matches(dbKey)) { "malformed store key" }
        require(phrase == null || PHRASE.matches(phrase)) { "malformed recovery phrase" }
        accounts.forEach { require(KEY.matches(it.signingKey) && KEY.matches(it.salt)) { "malformed account ${it.alias}" } }
    }

    /** The index the next account derived from the phrase takes. */
    val nextIndex: Int get() = (accounts.mapNotNull { it.index }.maxOrNull() ?: -1) + 1

    companion object {
        private val KEY = Regex("^0x[0-9a-f]{64}$")
        private val PHRASE = Regex("^[a-z]+( [a-z]+){11,23}$")
    }
}

class Vault(private val file: File, private val box: SecretBox, private val random: SecureRandom = SecureRandom()) {
    private val json = Json { ignoreUnknownKeys = false }

    fun exists(): Boolean = file.exists()

    /** Creates the vault with its first account, its recovery phrase if any, and a fresh store key. Fails if a vault exists. */
    fun create(first: AccountSecrets, phrase: String? = null): VaultContents {
        check(!exists()) { "a vault already exists" }
        val contents = VaultContents(accounts = listOf(first), dbKey = "0x" + hex(ByteArray(32).also(random::nextBytes)), phrase = phrase)
        write(contents)
        return contents
    }

    /** The vault before its last [upgrade], kept until the upgraded one has been opened, or changed, once ([write]). */
    private val previous = File(file.parentFile, file.name + ".previous")

    /** Whether the vault is sealed with a key the box replaces: [upgrade] after unlocking it. */
    fun needsUpgrade(): Boolean = exists() && box.isLegacy(file.readBytes())

    /**
     * Decrypts the vault. May throw an authentication-required error from the [SecretBox]. After an upgrade, the
     * first unlock that works with the new key forgets the old vault and key; one that fails (the new key lost or
     * refusing) goes back to the old vault, so an upgrade can never leave the wallet without a readable vault.
     */
    fun unlock(): VaultContents {
        val sealed = file.readBytes()
        if (box.isLegacy(sealed)) return decode(box.open(sealed))
        val contents = try {
            decode(box.open(sealed))
        } catch (e: Exception) {
            // The user declining is not a broken key: nothing to go back to.
            if (e is AuthenticationCancelled || e is AuthenticationRequired || !previous.exists()) throw e
            return decode(box.open(previous.readBytes())).also { check(previous.renameTo(file)) { "could not restore the vault" } }
        }
        if (previous.exists()) {
            previous.delete()
            box.dropLegacy()
        }
        return contents
    }

    /**
     * Seals [contents] again with the box's current key, keeping the vault as it was until the new one has been
     * opened ([unlock]).
     */
    fun upgrade(contents: VaultContents) {
        check(needsUpgrade()) { "the vault is already sealed with the current key" }
        file.copyTo(previous, overwrite = true)
        seal(contents)
    }

    private fun decode(plain: ByteArray): VaultContents = json.decodeFromString(VaultContents.serializer(), plain.decodeToString())

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
        previous.delete()
    }

    /**
     * Saves a change. Only [upgrade] keeps the vault it replaces, since it holds the same contents: going back to it
     * after any other change would undo that change (an imported key lost). So once a change is saved with the new key,
     * the vault kept from before the upgrade and the old key go.
     */
    private fun write(contents: VaultContents) {
        val replacesOldKey = needsUpgrade()
        seal(contents)
        if (!replacesOldKey && !previous.exists()) return
        if (previous.exists()) check(previous.delete()) { "could not drop the vault kept from before the upgrade" }
        box.dropLegacy()
    }

    private fun seal(contents: VaultContents) {
        val sealed = box.seal(json.encodeToString(VaultContents.serializer(), contents).encodeToByteArray())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealed)
        check(tmp.renameTo(file)) { "could not write the vault" }
    }

    companion object {
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** Salt 0: the address is then a function of the signing key alone, so restoring needs only the key. */
        const val ZERO_SALT = "0x0000000000000000000000000000000000000000000000000000000000000000"

        /** Order of the Grumpkin scalar field: Aztec signing keys are elements of it. */
        val GRUMPKIN_ORDER = java.math.BigInteger("30644e72e131a029b85045b68181585d97816a916871ca8d3c208c16d87cfd47", 16)

        /** Normalises a signing key typed or pasted by the user; throws unless it is a non-zero Grumpkin scalar. */
        fun parseSigningKey(input: String): String {
            // Spaces and line breaks inside are dropped: a key copied by hand in groups, or wrapped by a note app.
            val body = input.filterNot { it.isWhitespace() }.removePrefix("0x").lowercase()
            require(body.length == 64 && body.all { it in "0123456789abcdef" }) { "a signing key is 64 hexadecimal characters" }
            val value = java.math.BigInteger(body, 16)
            require(value.signum() > 0 && value < GRUMPKIN_ORDER) { "this is not a valid Aztec signing key" }
            return "0x$body"
        }

        /** A fresh signing key: 64 random bytes reduced modulo the field order (bias below 2^-250). */
        fun newSigningKey(random: SecureRandom = SecureRandom()): String {
            val wide = java.math.BigInteger(1, ByteArray(64).also(random::nextBytes))
            val key = wide.mod(GRUMPKIN_ORDER).takeIf { it.signum() > 0 } ?: java.math.BigInteger.ONE
            return "0x" + key.toString(16).padStart(64, '0')
        }
    }
}
