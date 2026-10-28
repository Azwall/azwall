package dev.azwall.core.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Thrown when the Keystore key needs the user to authenticate (biometric or device credential) first. */
class AuthenticationRequired(cause: Throwable) : Exception("authenticate to unlock the wallet", cause)

/**
 * Thrown when Android erased the vault's key, as it does when the phone's screen lock is removed: the vault can never
 * be opened again, and only the recovery phrase brings the wallet back.
 */
class VaultKeyLost(cause: Throwable) : Exception(
    "This phone's screen lock was turned off or reset, which erased the key protecting your wallet. Restore it with your recovery phrase.",
    cause,
)

/**
 * AES-256-GCM with a key that never leaves the Android Keystore and only works for [AUTH_SECONDS] after the
 * user authenticated with a strong biometric or the device credential. Sealed format: IV length, IV, ciphertext.
 */
class KeystoreSecretBox(private val alias: String = "azwall.vault") : SecretBox {
    override fun seal(plain: ByteArray): ByteArray = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain)
        ByteBuffer.allocate(4 + cipher.iv.size + sealed.size).putInt(cipher.iv.size).put(cipher.iv).put(sealed).array()
    }

    override fun open(sealed: ByteArray): ByteArray = guarded {
        val buffer = ByteBuffer.wrap(sealed)
        val iv = ByteArray(buffer.int.also { require(it in 12..32) { "corrupt vault" } }).also(buffer::get)
        val body = ByteArray(buffer.remaining()).also(buffer::get)
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(body)
    }

    /** Deletes the Keystore key (with the vault file, on wipe). */
    fun deleteKey() {
        keyStore().deleteEntry(alias)
    }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: UserNotAuthenticatedException) {
        throw AuthenticationRequired(e)
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw VaultKeyLost(e)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey = (keyStore().getKey(alias, null) as? SecretKey) ?: generate()

    private fun generate(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** How long one authentication unlocks the key (unlock, then saving a new account). */
        const val AUTH_SECONDS = 120
    }
}
