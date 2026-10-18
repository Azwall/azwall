package dev.azwall.app

import android.app.Application

class AzwallApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
