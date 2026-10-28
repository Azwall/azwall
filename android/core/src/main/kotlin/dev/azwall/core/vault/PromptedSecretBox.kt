package dev.azwall.core.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Thrown when the user declines (or fails) the authentication an operation of the vault's key asked for. */
class AuthenticationCancelled : Exception("authentication was cancelled")

/**
 * Asks the user to authenticate for one operation of [cipher] (BiometricPrompt with its CryptoObject) and returns
 * the cipher, now usable once; throws [AuthenticationCancelled] when they decline. Blocks: never on the main thread.
 */
fun interface CipherAuthorizer {
    fun authorize(cipher: Cipher, title: String): Cipher
}

/**
 * AES-256-GCM with a Keystore key that works for one operation per authentication (biometric or device
 * credential), bound to that operation: a recent unlock of the phone, or of the app, opens nothing. Sealed format:
 * [MAGIC], IV length, IV, ciphertext; the magic byte tells it from [KeystoreSecretBox]'s, which it replaces.
 */
class PromptedSecretBox(private val authorizer: CipherAuthorizer, private val alias: String = "azwall.vault.v2") : SecretBox {
    override fun seal(plain: ByteArray): ByteArray = guarded {
        val cipher = authorizer.authorize(Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }, "Save your wallet")
        val sealed = cipher.doFinal(plain)
        ByteBuffer.allocate(5 + cipher.iv.size + sealed.size).put(MAGIC).putInt(cipher.iv.size).put(cipher.iv).put(sealed).array()
    }

    override fun open(sealed: ByteArray): ByteArray = guarded {
        val buffer = ByteBuffer.wrap(sealed)
        require(buffer.get() == MAGIC) { "not sealed with the vault's current key" }
        val iv = ByteArray(buffer.int.also { require(it in 12..32) { "corrupt vault" } }).also(buffer::get)
        val body = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, keyToOpen(existing()), GCMParameterSpec(128, iv)) }
        val authorized = authorizer.authorize(cipher, "Unlock Azwall")
        openingWith { authorized.doFinal(body) }
    }

    fun deleteKey() {
        keyStore().deleteEntry(alias)
    }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw VaultKeyLost(e)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existing(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    /** Only sealing may make the key: a vault is opened with the one that sealed it, or not at all ([keyToOpen]). */
    private fun key(): SecretKey = existing() ?: generate()

    private fun generate(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // 0: every use needs its own authentication, through the CryptoObject of that very operation.
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** The first byte of a vault this box sealed. [KeystoreSecretBox]'s starts with an IV length: 0x00. */
        const val MAGIC: Byte = 0xA2.toByte()
    }
}

/**
 * The vault's box: seals with [current], opens what either sealed. A vault still under [legacy] (the key that opened
 * for two minutes after any authentication) is sealed again with [current] at its next unlock (Vault.upgrade).
 */
class UpgradingSecretBox(private val legacy: KeystoreSecretBox, private val current: PromptedSecretBox) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray = current.seal(plain)

    override fun open(sealed: ByteArray): ByteArray = if (isLegacy(sealed)) legacy.open(sealed) else current.open(sealed)

    override fun isLegacy(sealed: ByteArray): Boolean = sealed.firstOrNull() != PromptedSecretBox.MAGIC

    override fun dropLegacy() = legacy.deleteKey()

    /** Both keys, on wipe. */
    fun deleteKeys() {
        legacy.deleteKey()
        current.deleteKey()
    }
}
