package dev.azwall.app

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import dev.azwall.core.AzwallRuntime
import dev.azwall.core.DiagnosticLog
import dev.azwall.core.WalletBackend
import dev.azwall.core.vault.KeystoreSecretBox
import dev.azwall.core.vault.Vault
import dev.azwall.core.wallet.DappApprovals
import dev.azwall.core.wallet.PxeLauncher
import dev.azwall.core.wallet.SearchEngine
import java.io.File

/** What the app view model needs from the platform; replaced by fakes in tests. */
interface AppDeps {
    /** Loads the Rust core on first use (copies the CRS): call it off the main thread. */
    val backend: WalletBackend
    val vault: Vault
    val launcher: PxeLauncher
    val storeDir: File
    /** The user's answers to dApp requests, shared by the browser and the PXE hosts. */
    val approvals: DappApprovals
    var lastNetwork: String?
    /** Where the browser's address bar searches. */
    var searchEngine: SearchEngine

    /** A screen lock (PIN, pattern, password) is set: the vault's Keystore key cannot exist without one. */
    val deviceSecure: Boolean get() = true

    /** Deletes the vault and its Keystore key. */
    fun wipe()
}

class AppContainer(context: Context) : AppDeps {
    private val app = context.applicationContext
    private val box = KeystoreSecretBox()
    private val prefs = app.getSharedPreferences("azwall", Context.MODE_PRIVATE)

    override val backend: WalletBackend by lazy { AzwallRuntime.create(app) }
    override val vault = Vault(File(app.filesDir, "vault.bin"), box)
    override val approvals = DappApprovals()
    override val launcher: PxeLauncher = AndroidPxeLauncher(app, approvals) { backend }
    override val storeDir = File(app.filesDir, "wallet").also {
        // Kept with the store: what went wrong lately, exported only by the user.
        it.mkdirs()
        DiagnosticLog.shared.attach(File(it, "diagnostics.log"))
    }
    override val deviceSecure: Boolean get() = app.getSystemService(KeyguardManager::class.java).isDeviceSecure

    override var lastNetwork: String?
        get() = prefs.getString("network", null)
        set(value) = prefs.edit().putString("network", value).apply()

    override var searchEngine: SearchEngine
        get() = SearchEngine.named(prefs.getString("search_engine", null))
        set(value) = prefs.edit().putString("search_engine", value.name).apply()

    override fun wipe() {
        vault.wipe()
        box.deleteKey()
        storeDir.deleteRecursively()
        prefs.edit().clear().apply()
        // The PXE stores (encrypted with the vault's store key, useless without it) and the browser's sites data.
        Handler(Looper.getMainLooper()).post {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
}
