package dev.azwall.app

import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.AccountSecrets
import dev.azwall.core.pxe.PxeException
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
import dev.azwall.core.wallet.AutoLock
import dev.azwall.core.wallet.DappApprovals
import dev.azwall.core.wallet.PxeLauncher
import dev.azwall.core.wallet.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Locking on its own and starting a session again, on an unlocked wallet whose PXE host never starts. */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun reset() = Dispatchers.resetMain()

    private lateinit var saved: Vault

    private val network = NetworkInfo("testnet-v6", "Testnet", "6.0.0", "6.0.0", "node", "v6", true)

    private val backend = object : WalletBackend {
        override fun networks() = listOf(network)
        override fun defaultNetworkId() = network.id
        override fun checkNode(networkId: String) = NodeCheck("6.0.0", 0, true, emptyList())
        override fun pxeOpenParams(networkId: String) = "{}"
        override fun prepareProver(networkId: String) {}
        override fun prove(networkId: String, request: ByteArray) = request
        override fun newRecoveryPhrase() = error("not used")
        override fun normalizeRecoveryPhrase(phrase: String) = error("not used")
        override fun accountSigningKey(phrase: String, index: Int) = error("not used")
    }

    /** An unlocked wallet on the test clock. */
    private fun TestScope.unlocked(): AppViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val plain = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        }
        val vault = Vault(tmp.root.resolve("vault"), plain).apply { create(AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT)) }
        saved = vault
        val vm = AppViewModel(object : AppDeps {
            override val backend: WalletBackend get() = this@LifecycleTest.backend
            override val vault = vault
            override val launcher = PxeLauncher { throw PxeException("no PXE in this test") }
            override val storeDir: File = tmp.root
            override val approvals = DappApprovals()
            override var lastNetwork: String? = null
            override var searchEngine = SearchEngine.entries.first()
            override fun wipe() {}
        }, dispatcher)
        vm.boot()
        advanceUntilIdle()
        vm.unlock(auth = { true })
        advanceUntilIdle()
        assertTrue(vm.phase.value is AppPhase.Unlocked)
        return vm
    }

    @Test
    fun aWalletLeftInTheBackgroundLocksWithoutWaitingForTheAppToComeBack() = runTest {
        val vm = unlocked()
        vm.onBackground()
        advanceTimeBy(AutoLock.DEFAULT_MS - 1_000)
        runCurrent()
        assertTrue(vm.phase.value is AppPhase.Unlocked)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(AppPhase.Locked, vm.phase.value)
    }

    @Test
    fun comingBackInTimeKeepsTheWalletOpen() = runTest {
        val vm = unlocked()
        vm.onBackground()
        advanceTimeBy(60_000)
        vm.onForeground()
        advanceTimeBy(AutoLock.DEFAULT_MS)
        runCurrent()
        assertTrue(vm.phase.value is AppPhase.Unlocked)
    }

    @Test
    fun retryStartsANewSession() = runTest {
        val vm = unlocked()
        val failed = (vm.phase.value as AppPhase.Unlocked).session
        vm.restart()
        advanceUntilIdle()
        val again = (vm.phase.value as AppPhase.Unlocked).session
        assertNotSame(failed, again)
    }

    @Test
    fun accountsAddedBeforeARetryAreKeptByTheNextWrite() = runTest {
        // From the 2026-10-28 audit (docs/audits): an imported key was dropped from the vault after a retry.
        val vm = unlocked()
        vm.addAccount("second", "0x" + "02".repeat(32)) { true }.join()
        advanceUntilIdle()
        vm.restart()
        advanceUntilIdle()
        vm.renameAccount("unknown", "x") { true }.join()
        vm.addAccount("third", "0x" + "03".repeat(32)) { true }.join()
        advanceUntilIdle()
        assertEquals(listOf("main", "second", "third"), saved.unlock().accounts.map { it.alias })
    }
}
