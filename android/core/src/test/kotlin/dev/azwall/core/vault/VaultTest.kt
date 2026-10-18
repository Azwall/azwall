package dev.azwall.core.vault

import dev.azwall.core.pxe.AccountSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** XOR "encryption": enough to prove the vault never writes plaintext and always goes through the box. */
private class XorBox : SecretBox {
    var opened = 0

    override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

    override fun open(sealed: ByteArray): ByteArray {
        opened++
        return seal(sealed)
    }
}

class VaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = "0x" + "ab".repeat(32)
    private val main = AccountSecrets("main", key, Vault.ZERO_SALT)

    private fun vault(box: SecretBox = XorBox()) = Vault(tmp.root.resolve("vault.bin"), box)

    @Test
    fun createThenUnlockRoundTrips() {
        val box = XorBox()
        val created = vault(box).create(main)
        assertTrue(created.dbKey.matches(Regex("^0x[0-9a-f]{64}$")))
        assertEquals(created, vault(box).unlock())
        assertEquals(1, box.opened)
    }

    @Test
    fun theFileNeverHoldsPlaintext() {
        vault().create(main)
        val raw = tmp.root.resolve("vault.bin").readBytes().decodeToString()
        assertFalse(raw.contains("ab".repeat(32)))
        assertFalse(raw.contains("main"))
    }

    @Test
    fun accountsAreAddedAndRenamedWithoutDuplicates() {
        val v = vault()
        val second = AccountSecrets("savings", "0x" + "cd".repeat(32), Vault.ZERO_SALT)
        val contents = v.addAccount(v.create(main), second)
        assertEquals(listOf("main", "savings"), v.unlock().accounts.map { it.alias })
        assertThrows(IllegalArgumentException::class.java) { v.addAccount(contents, second.copy(alias = "other")) }
        assertThrows(IllegalArgumentException::class.java) { v.addAccount(contents, second.copy(signingKey = "0x" + "ef".repeat(32))) }
        assertEquals("daily", v.renameAccount(contents, key, " daily ").accounts.first().alias)
    }

    @Test
    fun createRefusesToOverwriteAndWipeDeletes() {
        val v = vault()
        v.create(main)
        assertThrows(IllegalStateException::class.java) { v.create(main) }
        v.wipe()
        assertFalse(v.exists())
    }

    @Test
    fun signingKeysAreNormalised() {
        val valid = "0x" + "12".repeat(32)
        assertEquals(valid, Vault.parseSigningKey("  " + "12".repeat(32) + " "))
        assertEquals("0x" + "1a".repeat(32), Vault.parseSigningKey("0x" + "1A".repeat(32)))
        assertThrows(IllegalArgumentException::class.java) { Vault.parseSigningKey("0x1234") }
        assertThrows(IllegalArgumentException::class.java) { Vault.parseSigningKey("zz".repeat(32)) }
        assertThrows(IllegalArgumentException::class.java) { Vault.parseSigningKey("ff".repeat(32)) }
        assertThrows(IllegalArgumentException::class.java) { Vault.parseSigningKey("00".repeat(32)) }
    }

    @Test
    fun newKeysAreValidAndDistinct() {
        val keys = (1..50).map { Vault.newSigningKey() }
        keys.forEach { assertEquals(it, Vault.parseSigningKey(it)) }
        assertEquals(50, keys.toSet().size)
    }

    @Test
    fun malformedContentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { VaultContents(accounts = listOf(main), dbKey = "0x12") }
        assertThrows(IllegalArgumentException::class.java) { VaultContents(accounts = listOf(main.copy(salt = "0")), dbKey = key) }
    }
}
