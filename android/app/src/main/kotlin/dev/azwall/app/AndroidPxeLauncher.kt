package dev.azwall.app

import android.content.Context
import dev.azwall.core.NetworkInfo
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.NativeHandler
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.pxe.PxeHost
import dev.azwall.core.wallet.PxeLauncher
import dev.azwall.core.wallet.RunningPxe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/** Starts a network's PXE bundle in a hidden web view and answers its `prove` calls with the Rust prover. */
class AndroidPxeLauncher(private val context: Context, private val backend: () -> WalletBackend) : PxeLauncher {
    override suspend fun launch(network: NetworkInfo): RunningPxe {
        val host = PxeHost.start(context, network.pxeBundle, mapOf("prove" to prove()))
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
        val response = withContext(Dispatchers.Default) { backend().prove(network, request) }
        buildJsonObject { put("response", Base64.getEncoder().encodeToString(response)) }
    }
}
