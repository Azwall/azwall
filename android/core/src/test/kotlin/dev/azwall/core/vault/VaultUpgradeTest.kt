package dev.azwall.core.vault

import dev.azwall.core.pxe.AccountSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Sealing again with the key bound to each operation, without ever leaving the wallet without a readable vault. */
class VaultUpgradeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The old key's format: a first byte of 0. */
    private object Legacy : SecretBox {
        override fun seal(plain: ByteArray) = byteArrayOf(0) + plain
        override fun open(sealed: ByteArray) = sealed.copyOfRange(1, sealed.size)
    }

    /** Seals with the new key (its magic byte first), opens both; the new key can be made to fail or be declined. */
    private class Upgrading : SecretBox {
        var current: (() -> Unit)? = null
        var dropped = false

        override fun seal(plain: ByteArray) = byteArrayOf(PromptedSecretBox.MAGIC) + plain

        override fun open(sealed: ByteArray): ByteArray {
            if (!isLegacy(sealed)) current?.invoke()
            return sealed.copyOfRange(1, sealed.size)
        }

        override fun isLegacy(sealed: ByteArray) = sealed.firstOrNull() != PromptedSecretBox.MAGIC

        override fun dropLegacy() {
            dropped = true
        }
    }

    private val file get() = tmp.root.resolve("vault.bin")
    private val previous get() = tmp.root.resolve("vault.bin.previous")
    private val first = AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT)

    /** A vault sealed with the old key, as every wallet has before the update. */
    private fun legacyVault(): VaultContents = Vault(file, Legacy).create(first)

    @Test
    fun anOldVaultIsSealedAgainAndTheOldKeyGoesOnceTheNewOneOpensIt() {
        val created = legacyVault()
        val box = Upgrading()
        val vault = Vault(file, box)
        assertTrue(vault.needsUpgrade())
        vault.upgrade(vault.unlock())
        assertFalse(vault.needsUpgrade())
        assertTrue("the old vault is kept until the new one opens", previous.exists())
        assertEquals(created, vault.unlock())
        assertFalse(previous.exists())
        assertTrue(box.dropped)
    }

    @Test
    fun aNewKeyThatFailsGivesBackTheOldVault() {
        val created = legacyVault()
        val box = Upgrading()
        val vault = Vault(file, box)
        vault.upgrade(vault.unlock())
        box.current = { throw VaultKeyLost(IllegalStateException("key invalidated")) }
        assertEquals(created, vault.unlock())
        // Back as it was: the next unlock tries the upgrade again, and the old key is still there.
        assertTrue(vault.needsUpgrade())
        assertFalse(box.dropped)
    }

    @Test
    fun aDeclinedPromptIsNotTakenForABrokenKey() {
        legacyVault()
        val box = Upgrading()
        val vault = Vault(file, box)
        vault.upgrade(vault.unlock())
        box.current = { throw AuthenticationCancelled() }
        assertThrows(AuthenticationCancelled::class.java) { vault.unlock() }
        assertTrue("nothing given up: the old vault waits for the next unlock", previous.exists())
        assertFalse(vault.needsUpgrade())
    }

    @Test
    fun anAccountAddedBeforeTheUpgradeIsNeverRolledBack() {
        legacyVault()
        val box = Upgrading()
        val vault = Vault(file, box)
        vault.addAccount(vault.unlock(), AccountSecrets("second", "0x" + "cd".repeat(32), Vault.ZERO_SALT))
        assertFalse(vault.needsUpgrade())
        assertFalse("a vault without the new account is not kept to go back to", previous.exists())
        assertTrue(box.dropped)
        box.current = { throw VaultKeyLost(IllegalStateException("key invalidated")) }
        assertThrows(VaultKeyLost::class.java) { vault.unlock() }
        box.current = null
        assertEquals(2, vault.unlock().accounts.size)
    }

    /** From the 2026-10-28 independent audit (A01): a failing new key used to roll an imported account back. */
    @Test
    fun anAccountAddedAfterTheUpgradeIsNeverRolledBack() {
        legacyVault()
        val box = Upgrading()
        val vault = Vault(file, box)
        val original = vault.unlock()
        vault.upgrade(original)
        vault.addAccount(original, AccountSecrets("imported", "0x" + "02".repeat(32), Vault.ZERO_SALT))
        assertFalse(previous.exists())
        assertTrue(box.dropped)
        box.current = { throw java.security.ProviderException("temporary provider failure") }
        assertThrows(java.security.ProviderException::class.java) { vault.unlock() }
        box.current = null
        assertEquals(listOf("main", "imported"), vault.unlock().accounts.map { it.alias })
    }

    @Test
    fun aRenameAfterTheUpgradeIsNeverRolledBack() {
        legacyVault()
        val box = Upgrading()
        val vault = Vault(file, box)
        val original = vault.unlock()
        vault.upgrade(original)
        vault.renameAccount(original, first.signingKey, "renamed")
        box.current = { throw VaultKeyLost(IllegalStateException("key invalidated")) }
        assertThrows(VaultKeyLost::class.java) { vault.unlock() }
        box.current = null
        assertEquals("renamed", vault.unlock().accounts.single().alias)
    }

    @Test
    fun aVaultCreatedNowIsSealedWithTheNewKeyFromTheStart() {
        val vault = Vault(file, Upgrading())
        vault.create(first)
        assertFalse(vault.needsUpgrade())
        assertThrows(IllegalStateException::class.java) { vault.upgrade(vault.unlock()) }
    }
}
