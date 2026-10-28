package dev.azwall.core.wallet

import java.net.URLDecoder

/**
 * What a scanned QR code (or a pasted link) asks for. Either a bare Aztec address, or a payment request URI in the
 * spirit of Ethereum's EIP-681 and Zcash's ZIP-321:
 *
 *     aztec:<address>?network=testnet-v6&token=<token address>&amount=1.5
 *
 * Every parameter is optional; `amount` is in the token's units (not base units). Addresses differ between
 * Aztec versions, hence `network`: the wallet warns when it is not the open one.
 *
 * A token is shared the same way, its contract in place of the address: `aztec:<token address>?network=testnet-v6`.
 */
data class PaymentRequest(val address: String, val network: String? = null, val token: String? = null, val amount: String? = null) {
    companion object {
        private val ADDRESS = Regex("^0x[0-9a-fA-F]{64}$")
        private const val SCHEME = "aztec:"

        fun isAddress(text: String): Boolean = ADDRESS.matches(text)

        /** Percent-decoding only (a `+` stays a `+`, as on iOS); malformed escapes leave the text as it was. */
        // Malformed escapes in a scanned or pasted URI are expected input, not an error.
        private fun percentDecoded(v: String): String = runCatching { URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") }.getOrDefault(v)

        /** The request in [text], or null when it is neither an address nor an `aztec:` URI with one. */
        fun parse(text: String): PaymentRequest? {
            val trimmed = text.trim()
            if (isAddress(trimmed)) return PaymentRequest(trimmed)
            if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
            val rest = trimmed.substring(SCHEME.length).removePrefix("//")
            val address = rest.substringBefore('?')
            if (!isAddress(address)) return null
            val params = rest.substringAfter('?', "").split('&').filter { '=' in it }.associate { pair ->
                val (k, v) = pair.split('=', limit = 2)
                k.lowercase() to percentDecoded(v)
            }
            val token = params["token"]?.takeIf(::isAddress)
            val amount = params["amount"]?.takeIf { it.matches(Regex("^\\d+(\\.\\d+)?$")) }
            return PaymentRequest(address, params["network"]?.takeIf { it.isNotBlank() }, token, amount)
        }
    }

    /** The token a code is about when adding one: the token it requests, else its address (a shared token's code). */
    val tokenAddress: String get() = token ?: address

    /** What to tell the user when the code was made for another network than [current], or null. */
    fun networkNote(current: String): String? = network?.takeIf { it != current }?.let { "This code is for $it, not $current" }

    /** The URI form: what a Receive screen encodes in its QR code when it asks for something specific. */
    fun toUri(): String {
        val params = listOfNotNull(network?.let { "network=$it" }, token?.let { "token=$it" }, amount?.let { "amount=$it" })
        return if (params.isEmpty()) address else "$SCHEME$address?${params.joinToString("&")}"
    }
}
