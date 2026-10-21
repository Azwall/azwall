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
    }
}
