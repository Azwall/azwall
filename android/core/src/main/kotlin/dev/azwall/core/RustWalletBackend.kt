package dev.azwall.core

import dev.azwall.core.ffi.AzwallException
import dev.azwall.core.ffi.Network
import dev.azwall.core.ffi.NodeStatus
import dev.azwall.core.ffi.WalletCore

/** [WalletBackend] over the Rust core (uniffi bindings). */
class RustWalletBackend(private val core: WalletCore) : WalletBackend {

    override fun networks(): List<NetworkInfo> = core.networks().map { it.toInfo() }

    override fun defaultNetworkId(): String = core.defaultNetworkId()

    override fun checkNode(networkId: String): NodeCheck = rust { core.checkNode(networkId).toCheck() }

    override fun prepareProver(networkId: String) = rust { core.prepareProver(networkId) }

    override fun prove(networkId: String, request: ByteArray): ByteArray = rust { core.prove(networkId, request) }

    companion object {
        fun create(configJson: String, nativeLibDir: String, crsDir: String, proverThreads: Int = 0): RustWalletBackend =
            rust { RustWalletBackend(WalletCore(configJson, nativeLibDir, crsDir, proverThreads.toUInt())) }
    }
}

private inline fun <T> rust(block: () -> T): T =
    try {
        block()
    } catch (e: AzwallException) {
        throw WalletException(e.message ?: e.javaClass.simpleName, e)
    }

internal fun Network.toInfo() = NetworkInfo(
    id = id,
    label = label,
    aztecVersion = aztecVersion,
    barretenbergVersion = barretenbergVersion,
    rpcHost = hostOf(rpcUrl),
    proverSupported = proverSupported,
)

internal fun NodeStatus.toCheck() = NodeCheck(
    nodeVersion = nodeVersion,
    rollupVersion = rollupVersion.toLong(),
    compatible = compatible,
    mismatches = mismatches,
)
