package dev.azwall.app

import android.content.Context
import dev.azwall.core.AzwallRuntime
import dev.azwall.core.WalletBackend
import dev.azwall.core.vault.KeystoreSecretBox
import dev.azwall.core.vault.Vault
import dev.azwall.core.wallet.PxeLauncher
import java.io.File

/** What the app view model needs from the platform; replaced by fakes in tests. */
interface AppDeps {
    /** Loads the Rust core on first use (copies the CRS): call it off the main thread. */
    val backend: WalletBackend
    val vault: Vault
    val launcher: PxeLauncher
    val storeDir: File
    var lastNetwork: String?

    /** Deletes the vault and its Keystore key. */
    fun wipe()
}

class AppContainer(context: Context) : AppDeps {
    private val app = context.applicationContext
    private val box = KeystoreSecretBox()
    private val prefs = app.getSharedPreferences("azwall", Context.MODE_PRIVATE)

    override val backend: WalletBackend by lazy { AzwallRuntime.create(app) }
    override val vault = Vault(File(app.filesDir, "vault.bin"), box)
    override val launcher: PxeLauncher = AndroidPxeLauncher(app) { backend }
    override val storeDir = File(app.filesDir, "wallet")

    override var lastNetwork: String?
        get() = prefs.getString("network", null)
        set(value) = prefs.edit().putString("network", value).apply()

    override fun wipe() {
        vault.wipe()
        box.deleteKey()
        storeDir.deleteRecursively()
        prefs.edit().clear().apply()
    }
}
