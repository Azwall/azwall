package dev.azwall.core

import android.content.Context
import java.io.File

/** Builds the [WalletBackend] from what the APK bundles. */
object AzwallRuntime {
    /** Generated at build time from networks.json and .env (rust/azwall-core, azwall-config). */
    const val CONFIG_ASSET = "azwall-config.json"
    const val CRS_ASSET_DIR = "crs"
    private val CRS_FILES = listOf("g1_compressed.dat", "g2.dat", "grumpkin_g1_v2.dat")

    fun create(context: Context, proverThreads: Int = 0): WalletBackend {
        val config = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
        return RustWalletBackend.create(
            configJson = config,
            nativeLibDir = context.applicationInfo.nativeLibraryDir,
            crsDir = installCrs(context).absolutePath,
            proverThreads = proverThreads,
        )
    }

    /**
     * Barretenberg reads the CRS from files, so the bundled assets are copied once to app storage.
     * Missing assets are skipped: the prover then reports which file to fetch.
     */
    private fun installCrs(context: Context): File {
        val dir = File(context.filesDir, CRS_ASSET_DIR).apply { mkdirs() }
        val bundled = context.assets.list(CRS_ASSET_DIR).orEmpty().toSet()
        for (name in CRS_FILES.filter { it in bundled }) {
            val target = File(dir, name)
            val size = context.assets.openFd("$CRS_ASSET_DIR/$name").use { it.length }
            if (target.length() == size) continue
            context.assets.open("$CRS_ASSET_DIR/$name").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return dir
    }
}
