package dev.azwall.app

import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.AccountSecrets
import dev.azwall.core.pxe.PxeCaller
import dev.azwall.core.pxe.PxeClient
import dev.azwall.core.pxe.PxeEvent
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
import dev.azwall.core.wallet.AutoLock
import dev.azwall.core.wallet.DappApprovals
import dev.azwall.core.wallet.PxeLauncher
import dev.azwall.core.wallet.RunningPxe
import dev.azwall.core.wallet.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * From the 2026-10-28 store-release audit (F15, F16): the background auto-lock used to be skipped for good when a proof
 * was running as it fired, and ignored a dApp's transaction. It now waits for any transaction on its way, then locks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoLockTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun reset() = Dispatchers.resetMain()

    private val network = NetworkInfo("testnet-v6", "Testnet", "6.0.0", "6.0.0", "node", "v6", true)
    private val events = MutableSharedFlow<PxeEvent>(extraBufferCapacity = 8)

    private val caller = PxeCaller { method, params ->
        Json.parseToJsonElement(
            when (method) {
                "open" -> """{"nodeVersion":"6.0.0","l1ChainId":1,"rollupVersion":2,"vkTreeRoot":"0x","protocolContractsHash":"0x","blockNumber":10,"fees":"sponsored"}"""
                "addAccount" -> """{"address":"addr-main"}"""
                else -> "null"
            },
        )
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

    private fun TestScope.unlocked(): AppViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val plain = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        }
        val vault = Vault(tmp.root.resolve("vault"), plain).apply { create(AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT)) }
        val launcher = PxeLauncher {
            object : RunningPxe {
                override val client = PxeClient(caller)
                override val events = this@AutoLockTest.events
                override fun stop() {}
            }
        }
        val vm = AppViewModel(object : AppDeps {
            override val backend: WalletBackend = this@AutoLockTest.backend
            override val vault = vault
            override val launcher = launcher
            override val storeDir = tmp.root
            override val approvals = DappApprovals()
            override var lastNetwork: String? = null
            override var searchEngine = SearchEngine.DEFAULT
            override fun wipe() {}
        }, dispatcher)
        vm.boot()
        advanceUntilIdle()
        vm.unlock { true }
        advanceUntilIdle()
        assertTrue(vm.phase.value is AppPhase.Unlocked)
        return vm
    }

    private fun dappTx(status: String) =
        PxeEvent("dappTx", Json.parseToJsonElement("""{"id":"t1","account":"addr-main","origin":"https://x.example","summary":"vote","status":"$status"}"""))

    @Test
    fun theAutoLockWaitsForADappTransactionOnItsWayThenLocks() = runTest {
        val vm = unlocked()
        events.emit(dappTx("proving"))
        runCurrent()
        vm.onBackground()
        advanceTimeBy(AutoLock.DEFAULT_MS + 60_000)
        runCurrent()
        assertTrue("locking now would stop the transaction midway", vm.phase.value is AppPhase.Unlocked)
        events.emit(dappTx("failed"))
        runCurrent()
        assertEquals(AppPhase.Locked, vm.phase.value)
    }

    /** Re-audit (R5): a dApp transaction that never ends (its site's wait never returns) must not keep the wallet open. */
    @Test
    fun aTransactionThatNeverEndsHoldsTheLockTenMinutesAtMost() = runTest {
        val vm = unlocked()
        events.emit(dappTx("proving"))
        runCurrent()
        vm.onBackground()
        advanceTimeBy(AutoLock.DEFAULT_MS + AutoLock.MAX_SEND_WAIT_MS - 60_000)
        runCurrent()
        assertTrue(vm.phase.value is AppPhase.Unlocked)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(AppPhase.Locked, vm.phase.value)
    }
}
