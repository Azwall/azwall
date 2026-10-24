package dev.azwall.app

import dev.azwall.core.WalletBackend
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
import dev.azwall.core.wallet.ApprovalKind
import dev.azwall.core.wallet.DappApprovals
import dev.azwall.core.wallet.PxeLauncher
import dev.azwall.core.wallet.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun model(secure: Boolean = true) = AppViewModel(object : AppDeps {
        override val backend: WalletBackend get() = error("not used")
        override val vault = Vault(tmp.root.resolve("vault"), object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        })
        override val launcher = PxeLauncher { error("not used") }
        override val storeDir: File = tmp.root
        override val approvals = DappApprovals()
        override var lastNetwork: String? = null
        override var searchEngine = SearchEngine.entries.first()
        override val deviceSecure = secure
        override fun wipe() {}
    })

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
}
