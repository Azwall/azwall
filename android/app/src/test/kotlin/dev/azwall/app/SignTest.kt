package dev.azwall.app

import dev.azwall.core.WalletBackend
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
import dev.azwall.core.vault.VaultKeyLost
import dev.azwall.core.wallet.ApprovalKind
import dev.azwall.core.wallet.DappApprovals
import dev.azwall.core.wallet.PxeLauncher
import dev.azwall.core.wallet.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SignTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private val plain = object : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    private var wiped = false

    private fun model(secure: Boolean = true, box: SecretBox = plain, io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) = AppViewModel(object : AppDeps {
        override val backend: WalletBackend get() = error("not used")
        override val vault = Vault(tmp.root.resolve("vault"), box)
        override val launcher = PxeLauncher { error("not used") }
        override val storeDir: File = tmp.root
        override val approvals = DappApprovals()
        override var lastNetwork: String? = null
        override var searchEngine = SearchEngine.entries.first()
        override val deviceSecure = secure
        override fun wipe() {
            wiped = true
        }
    }, io)

    @Test
    fun aDeclinedFingerprintSendsNothing() {
        val vm = model()
        var sent = false
        var closed = false
        vm.sign(auth = { false }, onAuthorized = { closed = true }) { sent = true }
        assertFalse(sent)
        // The form stays open, with what the user typed.
        assertFalse(closed)
        assertEquals("authentication was cancelled", vm.message.value)
    }

    @Test
    fun withoutAScreenLockTheWalletIsNotCreatedAndTheUserIsToldWhy() {
        val vm = model(secure = false)
        // The backend is never reached: it would throw "not used".
        vm.createWallet("some phrase", "Account 1", auth = { true })
        assertEquals(NoScreenLock().message, vm.message.value)
        assertFalse(vm.busy.value)
    }

    @Test
    fun aVaultWhoseKeyAndroidErasedLeadsToRestoringFromThePhrase() {
        Vault(tmp.root.resolve("vault"), plain).create(dev.azwall.core.pxe.AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT))
        val lost = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray): ByteArray = throw VaultKeyLost(IllegalStateException("key permanently invalidated"))
        }
        // Off the IO pool: the phase is read right after each action.
        val vm = model(box = lost, io = Dispatchers.Main)
        vm.unlock(auth = { true })
        assertEquals(AppPhase.KeyLost, vm.phase.value)
        assertTrue(vm.message.value!!.contains("recovery phrase"))
        // Deleting waits for any save under way (VaultState.erase), off the main thread.
        kotlinx.coroutines.runBlocking { vm.startOver().join() }
        assertTrue(wiped)
        assertEquals(AppPhase.Onboarding, vm.phase.value)
    }

    /** 2026-10-28 store-release audit (F6): a vault that keeps failing must not leave the user on a lock screen with no way out. */
    @Test
    fun anUnlockThatKeepsFailingOffersTheWayToRestore() {
        Vault(tmp.root.resolve("vault"), plain).create(dev.azwall.core.pxe.AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT))
        val broken = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("Keystore operation failed")
        }
        val vm = model(box = broken, io = Dispatchers.Main)
        vm.unlock(auth = { true })
        assertTrue(vm.unlockFailed.value)
        vm.cannotUnlock()
        // Not known to be a lost key: it says so, and offers to try again first.
        assertEquals(AppPhase.CannotUnlock, vm.phase.value)
        vm.tryUnlockAgain()
        assertEquals(AppPhase.Locked, vm.phase.value)
        vm.cannotUnlock()
        // Re-audit (R4): the next wallet does not inherit the offer.
        kotlinx.coroutines.runBlocking { vm.startOver().join() }
        assertFalse(vm.unlockFailed.value)
        assertEquals(AppPhase.Onboarding, vm.phase.value)
    }

    @Test
    fun decliningTheUnlockOffersNothingMore() {
        Vault(tmp.root.resolve("vault"), plain).create(dev.azwall.core.pxe.AccountSecrets("main", "0x" + "ab".repeat(32), Vault.ZERO_SALT))
        val declined = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray): ByteArray = throw dev.azwall.core.vault.AuthenticationCancelled()
        }
        val vm = model(box = declined, io = Dispatchers.Main)
        vm.unlock(auth = { true })
        assertFalse(vm.unlockFailed.value)
    }

    @Test
    fun onlyDappSpendsAskForTheOwner() {
        assertEquals(listOf(ApprovalKind.TRANSACTION, ApprovalKind.AUTHWIT), ApprovalKind.entries.filter { it.signs })
    }

    @Test
    fun theOwnerGetsThroughToTheWallet() {
        val vm = model()
        var closed = false
        vm.sign(auth = { true }, onAuthorized = { closed = true }) { }
        // Past the owner check, the form closes; without an unlocked wallet the transaction stops there.
        assertTrue(closed)
        assertEquals("the wallet is locked", vm.message.value)
    }

    @Test
    fun aDeclinedRevealSaysSo() = runTest {
        val vm = model()
        assertTrue(runCatching { vm.revealKey("0x1", auth = { false }) }.isFailure)
        assertEquals("authentication was cancelled", vm.message.value)
        vm.consumeMessage()
        assertTrue(runCatching { vm.revealPhrase(auth = { false }) }.isFailure)
        assertEquals("authentication was cancelled", vm.message.value)
    }

    @Test
    fun aRecoveryPhraseThatCannotBeMadeSaysWhy() = runTest {
        val vm = model()
        // The fake backend throws "not used": onboarding stays on its first step, with the reason shown.
        assertTrue(runCatching { vm.newRecoveryPhrase() }.isFailure)
        assertEquals("not used", vm.message.value)
    }

    @Test
    fun aRevealedPhraseShowsNoMessage() = runTest {
        val vm = model()
        assertNull(vm.revealPhrase(auth = { true }))
        assertNull(vm.message.value)
    }
}
