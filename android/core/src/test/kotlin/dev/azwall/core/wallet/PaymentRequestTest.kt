package dev.azwall.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentRequestTest {
    private val me = "0x" + "ab".repeat(32)
    private val token = "0x" + "cd".repeat(32)

    @Test
    fun aBareAddressIsARequestWithoutDetails() {
        assertEquals(PaymentRequest(me), PaymentRequest.parse("  $me\n"))
    }

    @Test
    fun aUriCarriesNetworkTokenAndAmount() {
        val request = PaymentRequest.parse("aztec:$me?network=testnet-v6&token=$token&amount=1.5")!!
        assertEquals(PaymentRequest(me, "testnet-v6", token, "1.5"), request)
        assertEquals("aztec:$me?network=testnet-v6&token=$token&amount=1.5", request.toUri())
        assertEquals(me, PaymentRequest(me).toUri())
    }

    @Test
    fun anythingElseIsRejectedAndBadParametersIgnored() {
        assertNull(PaymentRequest.parse("https://example.com"))
        assertNull(PaymentRequest.parse("aztec:0x12"))
        assertEquals(PaymentRequest(me), PaymentRequest.parse("aztec:$me?token=nope&amount=-3"))
        // A crafted QR code must not crash the scanner: a bad escape is kept as text, then rejected as an amount.
        assertEquals(PaymentRequest(me, "a+b"), PaymentRequest.parse("aztec:$me?network=a+b&amount=1%zz"))
    }

    @Test
    fun aSharedTokenIsItsAddressWithTheNetwork() {
        val shared = PaymentRequest(token, "testnet-v6")
        assertEquals("aztec:$token?network=testnet-v6", shared.toUri())
        assertEquals(token, PaymentRequest.parse(shared.toUri())!!.tokenAddress)
        assertEquals(token, PaymentRequest.parse("aztec:$me?token=$token")!!.tokenAddress)
        assertNull(shared.networkNote("testnet-v6"))
        assertEquals("This code is for testnet-v6, not testnet-v5", shared.networkNote("testnet-v5"))
        assertNull(PaymentRequest(token).networkNote("testnet-v5"))
    }
}
