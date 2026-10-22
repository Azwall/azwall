package dev.azwall.app

import dev.azwall.core.WalletBackend
import dev.azwall.core.vault.SecretBox
import dev.azwall.core.vault.Vault
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

    private fun model() = AppViewModel(object : AppDeps {
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
        override fun wipe() {}
    })

    @Test
    fun aDeclinedFingerprintSendsNothing() {
        val vm = model()
        var sent = false
        vm.sign(auth = { false }) { sent = true }
        assertFalse(sent)
        assertEquals("authentication was cancelled", vm.message.value)
    }
}
