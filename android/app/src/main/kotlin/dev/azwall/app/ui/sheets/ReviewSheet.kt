package dev.azwall.app.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.PrimaryButton
import dev.azwall.app.ui.theme.MonoStyle
import dev.azwall.core.wallet.Review

/** The confirmation before a transaction is proven and sent: every detail once more, and nothing happens until Confirm. */
@Composable
fun ReviewSheet(review: Review, onDismiss: () -> Unit, onConfirm: () -> Unit) = Sheet(review.title, onDismiss, fullHeight = true) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        review.lines.forEach { (label, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // An address in full and in monospace: the one place to check it character by character.
                Text(value, style = if (value.startsWith("0x")) MonoStyle else MaterialTheme.typography.bodyLarge)
            }
        }
        PrimaryButton(review.confirm, onClick = onConfirm)
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
