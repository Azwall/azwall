package dev.azwall.core.wallet

import java.math.BigInteger

/** Claim secrets of Fee Juice bridge deposits, as the user types or pastes them. */
object ClaimSecrets {
    /** The BN254 scalar field: a secret is an element of it. */
    private val MODULUS = BigInteger("21888242871839275222246405745257275088548364400416034343698204186575808495617")

    /** `0x…` hex or decimal, spaces and a trailing newline ignored, as a 0x-prefixed 64-digit hex field element. */
    fun normalize(text: String): String {
        val t = text.trim().replace(" ", "")
        val value = when {
            t.startsWith("0x", ignoreCase = true) -> t.drop(2).toBigIntegerOrNull(16)
            else -> t.toBigIntegerOrNull()
        }
        require(value != null && value.signum() >= 0 && value < MODULUS) { "not a claim secret: enter it in hex (0x…) or as a number" }
        return "0x" + value.toString(16).padStart(64, '0')
    }
}
