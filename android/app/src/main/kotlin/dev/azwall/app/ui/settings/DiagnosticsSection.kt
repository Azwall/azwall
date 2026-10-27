package dev.azwall.app.ui.settings

import android.content.Intent
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.azwall.core.DiagnosticLog

/** What went wrong lately (DiagnosticLog), shared only when the user chooses to: no service receives it on its own. */
@Composable
internal fun DiagnosticsSection(appVersion: String) = Section("Diagnostics") {
    val context = LocalContext.current
    var count by remember { mutableIntStateOf(DiagnosticLog.shared.entries.size) }
    Line("Recorded problems", count.toString())
    Text(
        "Warnings and errors of the last days, kept on this phone only. Addresses and hashes are shortened and RPC links " +
            "cut to their host; no keys or recovery words are ever in it. You choose whom to send it to.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = {
        val text = DiagnosticLog.shared.export("Azwall $appVersion", "Android ${Build.VERSION.RELEASE} (${Build.MODEL})")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Azwall diagnostic log")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Export diagnostic log"))
    }) { Text("Export diagnostic log") }
    TextButton(enabled = count > 0, onClick = { DiagnosticLog.shared.clear(); count = 0 }) { Text("Clear diagnostic log") }
}
