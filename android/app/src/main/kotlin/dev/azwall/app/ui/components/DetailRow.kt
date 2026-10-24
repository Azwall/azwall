package dev.azwall.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.theme.MonoStyle

/**
 * One line of a detail sheet (confirmation, transaction, dApp request): its label, then its value in full, selectable.
 * Addresses, hashes and raw JSON in a fixed-width font: the place to check them character by character.
 */
@Composable
fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val mono = value.startsWith("0x") || label == "JSON"
        SelectionContainer { Text(value, style = if (mono) MonoStyle else MaterialTheme.typography.bodyLarge) }
    }
}
