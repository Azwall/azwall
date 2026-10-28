package dev.azwall.app

import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck
import dev.azwall.core.WalletBackend
import dev.azwall.core.pxe.AccountSecrets
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
import dev.azwall.core.wallet.DappApprovals
import dev.azwall.core.wallet.PxeLauncher
import dev.azwall.core.wallet.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * From the 2026-10-28 audits (docs/audits): a Retry, a lock or a network switch while an account is being saved
 * must not make the next save drop it, and deleting the wallet then must not let that save write it back. The save
 * is held mid-way, as the user's fingerprint holds it on a phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountRaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun reset() = Dispatchers.resetMain()

    private val network = NetworkInfo("testnet-v6", "Testnet", "6.0.0", "6.0.0", "node", "v6", true)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val seals = AtomicInteger()

    /** Holds the second seal (the first account added after unlock) until [release]. */
    private val box = object : SecretBox {
        override fun seal(plain: ByteArray): ByteArray {
            if (seals.incrementAndGet() == 2) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            return plain
        }

        override fun open(sealed: ByteArray) = sealed
    }

    private val saved by lazy { Vault(tmp.root.resolve("vault"), box) }

    private fun TestScope.unlocked(): AppViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        saved.create(AccountSecrets("first", "0x" + "01".repeat(32), Vault.ZERO_SALT))
        val backend = object : WalletBackend {
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
        val vm = AppViewModel(object : AppDeps {
            override val backend = backend
            override val vault = saved
            override val launcher = PxeLauncher { error("simulated engine failure") }
            override val storeDir = tmp.root
            override val approvals = DappApprovals()
            override var lastNetwork: String? = null
            override var searchEngine = SearchEngine.DEFAULT
            override fun wipe() = saved.wipe()
        }, dispatcher)
        vm.boot()
        advanceUntilIdle()
        vm.unlock { true }
        advanceUntilIdle()
        return vm
    }

    /** Starts importing `second` and returns once its save is held. */
    private fun TestScope.importingSecond(vm: AppViewModel) = vm.addAccount("second", "0x" + "02".repeat(32)) { true }.also {
        runCurrent()
        check(entered.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun retryWhileSavingDoesNotDropTheAccount() = runTest {
        val vm = unlocked()
        val adding = importingSecond(vm)
        try {
            vm.restart().join()
        } finally {
            release.countDown()
        }
        adding.join()
        vm.addAccount("third", "0x" + "03".repeat(32)) { true }.join()
        advanceUntilIdle()
        assertEquals(listOf("first", "second", "third"), saved.unlock().accounts.map { it.alias })
    }

    @Test
    fun lockingAndUnlockingWhileSavingDoesNotDropTheAccount() = runTest {
        val vm = unlocked()
        val adding = importingSecond(vm)
        vm.lock()
        // Unlocking waits for the save: it reads the vault that save leaves.
        val unlocking = vm.unlock { true }
        runCurrent()
        release.countDown()
        adding.join()
        unlocking.join()
        advanceUntilIdle()
        vm.addAccount("third", "0x" + "03".repeat(32)) { true }.join()
        advanceUntilIdle()
        assertEquals(listOf("first", "second", "third"), saved.unlock().accounts.map { it.alias })
    }

    /** From the independent audit (A04): the save used to land after the deletion and write the vault back. */
    @Test
    fun deletingTheWalletWhileSavingLeavesNoVault() = runTest {
        val vm = unlocked()
        val adding = importingSecond(vm)
        val wiping = vm.wipe { true }
        runCurrent()
        release.countDown()
        adding.join()
        wiping.join()
        advanceUntilIdle()
        assertEquals(AppPhase.Onboarding, vm.phase.value)
        assertFalse("the deleted vault was written back", saved.exists())
    }
}
