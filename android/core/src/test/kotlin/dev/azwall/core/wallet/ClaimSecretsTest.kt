package dev.azwall.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClaimSecretsTest {
    @Test
    fun hexAndDecimalSecretsBecomeOneForm() {
        val zero = "0x" + "0".repeat(64)
        assertEquals(zero, ClaimSecrets.normalize("0"))
        assertEquals(zero, ClaimSecrets.normalize(" 0x0\n"))
        assertEquals("0x" + "0".repeat(62) + "ff", ClaimSecrets.normalize("255"))
        assertEquals("0x" + "0".repeat(62) + "ff", ClaimSecrets.normalize("0XFF"))
    }

    @Test
    fun anythingButAFieldElementIsRefused() {
        for (bad in listOf("", "0xzz", "-1", "secret", "0x" + "f".repeat(64))) {
            assertThrows(bad, IllegalArgumentException::class.java) { ClaimSecrets.normalize(bad) }
        }
    }
}
