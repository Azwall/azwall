package dev.azwall.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.AccountSecrets
import dev.azwall.core.pxe.PxeCaller
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
import dev.azwall.core.wallet.DappApprovals
import dev.azwall.core.wallet.PxeLauncher
import dev.azwall.core.wallet.RunningPxe
import dev.azwall.core.wallet.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Found on the emulator: the back gesture closed the app mid-send, its view model was cleared, and the PXE host ran on
 * with the keys in memory and its stores locked, so the wallet could not open again ("the PXE is not open").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenGoneTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun reset() = Dispatchers.resetMain()

    private val network = NetworkInfo("testnet-v6", "Testnet", "6.0.0", "6.0.0", "node", "v6", true)

    /** Opens, registers accounts; anything else reads nothing. */
    private val caller = PxeCaller { method, params ->
        Json.parseToJsonElement(
            when (method) {
                "open" -> """{"nodeVersion":"6.0.0","l1ChainId":1,"rollupVersion":2,"vkTreeRoot":"0x","protocolContractsHash":"0x","blockNumber":10,"fees":"sponsored"}"""
                "addAccount" -> """{"address":"addr-${params["alias"]}"}"""
                else -> "null"
            },
        )
    }

    private val plain = object : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    private val backend = object : WalletBackend {
        override fun networks() = listOf(network)
        override fun defaultNetworkId() = network.id
        override fun checkNode(networkId: String) = NodeCheck("6.0.0", 0, true, emptyList())
        override fun pxeOpenParams(networkId: String) = "{}"
        override fun prepareProver(networkId: String) {}
        override fun prove(networkId: String, request: ByteArray) = request
        override fun newRecoveryPhrase() = error("unused")
        override fun normalizeRecoveryPhrase(phrase: String) = error("unused")
        override fun accountSigningKey(phrase: String, index: Int) = error("unused")
    }

    @Test
    fun theWalletEngineStopsWhenTheScreenIsGoneForGood() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var stopped = false
        val launcher = PxeLauncher {
            object : RunningPxe {
                override val client = PxeClient(caller)
                override val events = emptyFlow<PxeEvent>()
                override fun stop() { stopped = true }
            }
        }
        val vault = Vault(tmp.root.resolve("vault"), plain).apply { create(AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT)) }
        val deps = object : AppDeps {
            override val backend: WalletBackend = this@ScreenGoneTest.backend
            override val vault = vault
            override val launcher = launcher
            override val storeDir = tmp.root
            override val approvals = DappApprovals()
            override var lastNetwork: String? = null
            override var searchEngine = SearchEngine.DEFAULT
            override fun wipe() {}
        }
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(deps, dispatcher) as T
        })[AppViewModel::class.java]
        vm.boot()
        advanceUntilIdle()
        vm.unlock { true }
        advanceUntilIdle()
        assertTrue(vm.phase.value is AppPhase.Unlocked)
        store.clear()
        advanceUntilIdle()
        assertTrue("the PXE host ran on after the screen was gone", stopped)
    }
}
