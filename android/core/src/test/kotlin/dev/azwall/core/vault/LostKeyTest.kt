package dev.azwall.core.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

/**
 * From the 2026-10-28 store-release audit (F6): a vault whose Keystore key Android erased must lead to restoring from the
 * recovery phrase, not to a fresh key that fails "Tag mismatch" on every unlock with no way out.
 */
class LostKeyTest {
    @Test
    fun aMissingKeyIsALostKeyNeverANewOne() {
        assertThrows(VaultKeyLost::class.java) { keyToOpen(null) }
        val key = SecretKeySpec(ByteArray(32), "AES")
        assertSame(key, keyToOpen(key))
    }

    @Test
    fun aKeyThatDoesNotMatchTheVaultIsLostToo() {
        assertThrows(VaultKeyLost::class.java) { openingWith<Unit> { throw AEADBadTagException("Tag mismatch") } }
        assertEquals(7, openingWith { 7 })
    }
}
