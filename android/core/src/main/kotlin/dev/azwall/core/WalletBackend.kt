package dev.azwall.core

import dev.azwall.core.pxe.TokenInfo
import dev.azwall.core.wallet.KnownDapp
import java.net.URI

/** A network the app embeds, as the UI needs it. */
data class NetworkInfo(
    val id: String,
    val label: String,
    val aztecVersion: String,
    val barretenbergVersion: String,
    /** Host of the RPC endpoint only: the path may carry an API key. */
    val rpcHost: String,
    /** PXE host bundle: `assets/pxe/<pxeBundle>/index.html`. */
    val pxeBundle: String,
    val proverSupported: Boolean,
    /** Tokens followed from the start on this network (networks.json). */
    val knownTokens: List<TokenInfo> = emptyList(),
    /** dApps the browser's start page suggests on this network (networks.json). */
    val dapps: List<KnownDapp> = emptyList(),
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

    /** JSON `network` parameter of the PXE host's `open` call. */
    fun pxeOpenParams(networkId: String): String

    /** Loads the network's Barretenberg release ahead of the first proof. */
    fun prepareProver(networkId: String)

    /** Proves an AZS2 request from the network's PXE and returns the AZP1 response. */
    fun prove(networkId: String, request: ByteArray): ByteArray

    /** A new 24-word recovery phrase (docs/design/recovery-phrase.md). */
    fun newRecoveryPhrase(): String

    /** The phrase as stored (lowercase, single spaces); throws a message for the user if it is not valid. */
    fun normalizeRecoveryPhrase(phrase: String): String

    /** The signing key of account [index] of a phrase. */
    fun accountSigningKey(phrase: String, index: Int): String
}

internal fun hostOf(url: String): String =
    // Only for display: an unparseable URL is shown as such.
    runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "invalid URL"
