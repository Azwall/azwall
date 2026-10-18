package dev.azwall.app

import android.app.Application
import dev.azwall.core.AzwallRuntime
import dev.azwall.core.WalletBackend

class AzwallApplication : Application() {
    /** Created on first use, off the main thread (it copies the CRS and loads the Rust core). */
    val backend: WalletBackend by lazy { AzwallRuntime.create(this) }
}
