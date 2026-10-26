package dev.azwall.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The claim secret of a bridge deposit made with a secret of the user's own (deposits with the default secret, 0, are
 * claimed without it). Typed or pasted; the wallet keeps it and tries it on the account's deposits.
 */
@Composable
internal fun ClaimSecretDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var secret by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Claim secret") },
        text = {
            Column {
                Text(
                    "The secret whose hash you gave the Fee Juice portal when bridging, in hex (0x…) or as a number. " +
                        "It only lets someone credit your account, never take the deposit.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Secret") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onUse(secret) }, enabled = secret.isNotBlank()) { Text("Use it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
