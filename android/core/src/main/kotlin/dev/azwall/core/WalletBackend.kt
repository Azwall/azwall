package dev.azwall.core

import java.net.URI

/** A network the app embeds, as the UI needs it. */
data class NetworkInfo(
    val id: String,
    val label: String,
    val aztecVersion: String,
    val barretenbergVersion: String,
    /** Host of the RPC endpoint only: the path may carry an API key. */
    val rpcHost: String,
    val proverSupported: Boolean,
)

data class NodeCheck(
    val nodeVersion: String,
    val rollupVersion: Long,
    val compatible: Boolean,
    val mismatches: List<String>,
)

class WalletException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The wallet core as the app sees it. Every call blocks (network, proving): call it off the
 * main thread. Failures are reported as [WalletException].
 */
interface WalletBackend {
    fun networks(): List<NetworkInfo>

    fun defaultNetworkId(): String

    fun checkNode(networkId: String): NodeCheck

    /** Loads the network's Barretenberg release ahead of the first proof. */
    fun prepareProver(networkId: String)

    /** Proves an AZS2 request from the network's PXE and returns the AZP1 response. */
    fun prove(networkId: String, request: ByteArray): ByteArray
}

internal fun hostOf(url: String): String =
    runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "invalid URL"
