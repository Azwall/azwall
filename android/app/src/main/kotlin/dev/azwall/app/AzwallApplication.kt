package dev.azwall.app

import android.app.Application
import android.system.Os

class AzwallApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Barretenberg sizes its thread pool from this variable. setenv is not thread-safe, so it is set now, before
        // the app starts its other threads, rather than by the Rust core once they run (azwall-prover's engine.rs).
        Os.setenv("HARDWARE_CONCURRENCY", Runtime.getRuntime().availableProcessors().toString(), true)
    }
}
