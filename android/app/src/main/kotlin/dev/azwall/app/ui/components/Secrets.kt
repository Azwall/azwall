package dev.azwall.app.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** How long a copied secret stays in the clipboard. */
private const val CLEAR_AFTER_MS = 60_000L

/** Keeps the screen out of screenshots, screen recordings and the recent-apps thumbnail while it shows a secret. */
@Composable
fun SecureWindow() {
    val window = LocalContext.current.activity()?.window ?: return
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * Copies a secret: marked sensitive (Android 13+ hides it from the clipboard preview, keyboards keep it out of
 * their history), then cleared after a minute if it is still what the clipboard holds.
 */
fun copySecret(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("secret", text)
    val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
    clip.description.extras = PersistableBundle().apply { putBoolean(sensitive, true) }
    clipboard.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        // Only to clear our own copy: if the system refuses the read, the clip is left to its own expiry.
        val current = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
        if (current == text) clipboard.clearPrimaryClip()
    }, CLEAR_AFTER_MS)
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
