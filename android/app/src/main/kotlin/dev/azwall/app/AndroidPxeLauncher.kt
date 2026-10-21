package dev.azwall.app

import android.content.Context
import dev.azwall.core.NetworkInfo
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.NativeHandler
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.pxe.PxeHost
import dev.azwall.core.wallet.DappApprovals
import dev.azwall.core.wallet.ProofGate
import dev.azwall.core.wallet.PxeLauncher
import dev.azwall.core.wallet.RunningPxe
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Starts a network's PXE bundle in a hidden web view. Its `prove` calls go to the Rust prover, its `approve` calls
 * (dApp requests) to the user, through [DappApprovals].
 */
class AndroidPxeLauncher(
    private val context: Context,
    private val approvals: DappApprovals,
    private val backend: () -> WalletBackend,
) : PxeLauncher {
    override suspend fun launch(network: NetworkInfo): RunningPxe {
        val handlers = mapOf("prove" to prove(), "approve" to { params: JsonObject -> approvals.approve(params) })
        val host = PxeHost.start(context, network.pxeBundle, handlers)
        return object : RunningPxe {
            override val client = PxeClient { method, params -> host.channel.call(method, params) }
            override val events: Flow<PxeEvent> = host.channel.events

            override fun stop() = host.destroy()
        }
    }

    /** `prove({ network, request })`: base64 AZS2 request in, base64 AZP1 response out (rust/azwall-prover). */
    private fun prove(): NativeHandler = { params ->
        val network = params["network"]!!.jsonPrimitive.content
        val request = Base64.getDecoder().decode(params["request"]!!.jsonPrimitive.content)
        ProofGate.check()
        val response = withContext(Dispatchers.Default) { backend().prove(network, request) }
        // Cancelled while proving: the proof is dropped, so the PXE never sends the transaction.
        ProofGate.check()
        buildJsonObject { put("response", Base64.getEncoder().encodeToString(response)) }
    }
}
