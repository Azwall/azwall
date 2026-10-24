package dev.azwall.app.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.AccountAvatar
import dev.azwall.app.ui.components.PrimaryButton
import dev.azwall.app.ui.send.QrScanner
import dev.azwall.app.ui.shortAddress
import dev.azwall.app.ui.theme.MonoStyle
import dev.azwall.core.NetworkInfo
import dev.azwall.core.vault.Vault
import dev.azwall.core.wallet.AccountView
import dev.azwall.core.wallet.PaymentRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(title: String, onDismiss: () -> Unit, fullHeight: Boolean = false, back: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    // Tall content (a QR code) opens fully expanded: half open, its bottom would be cut off.
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(start = if (back) 8.dp else 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // A sheet opened over another (a request's details over its panel) shows the way back.
                if (back) IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

private val rowColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Every embedded network; each runs its own Aztec version. */
@Composable
fun NetworksSheet(networks: List<NetworkInfo>, current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) =
    Sheet("Networks", onDismiss) {
        Text(
            "Your keys are the same on every network, but each network runs its own Aztec version: " +
                "your address, balances and history are different on each.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        networks.forEach { n ->
            ListItem(
                headlineContent = { Text(n.label) },
                supportingContent = { Text("Aztec ${n.aztecVersion} · ${n.rpcHost}") },
                trailingContent = { if (n.id == current) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary) },
                colors = rowColors,
                modifier = Modifier.clickable(enabled = n.id != current) { onSelect(n.id) }.padding(horizontal = 8.dp),
            )
        }
    }

@Composable
fun AccountsSheet(
    accounts: List<AccountView>,
    selected: String?,
    onSelect: (String) -> Unit,
    onCreate: (alias: String, importedKey: String?) -> Unit,
    onDismiss: () -> Unit,
) = Sheet("Accounts", onDismiss) {
    var adding by rememberSaveable { mutableStateOf(false) }
    accounts.forEach { a ->
        ListItem(
            leadingContent = { AccountAvatar(a.address) },
            headlineContent = { Text(a.alias) },
            supportingContent = { Text(shortAddress(a.address), style = MonoStyle) },
            trailingContent = { if (a.address == selected) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary) },
            colors = rowColors,
            modifier = Modifier.clickable { onSelect(a.address) }.padding(horizontal = 8.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    if (adding) NewAccount(accounts.size + 1, onCreate) else TextButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
        Text("+ Add or import an account")
    }
}

@Composable
private fun NewAccount(number: Int, onCreate: (String, String?) -> Unit) {
    var alias by rememberSaveable { mutableStateOf("Account $number") }
    var key by remember { mutableStateOf("") }
    val keyError = key.takeIf { it.isNotBlank() }?.let { runCatching { Vault.parseSigningKey(it) }.exceptionOrNull()?.message }
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(alias, { alias = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key,
            { key = it.trim() },
            label = { Text("Signing key to import (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = keyError != null,
            supportingText = { Text(keyError ?: "Leave empty for the next account of your recovery phrase") },
            textStyle = MonoStyle,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(if (key.isBlank()) "Create account" else "Import account", enabled = keyError == null && alias.isNotBlank()) {
            onCreate(alias, key.ifBlank { null })
        }
    }
}

/** Follow a token by its contract address, typed, pasted or scanned from a shared token's QR code; the rest is read on chain. */
@Composable
fun AddTokenSheet(network: String, busy: Boolean, onAdd: (String) -> Unit, onDismiss: () -> Unit) = Sheet("Add a token", onDismiss) {
    var text by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val request = PaymentRequest.parse(text)
    val note = if (text.isNotEmpty() && request == null) "Not a token address" else request?.networkNote(network)
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            text,
            { text = it.trim() },
            label = { Text("Token contract address") },
            textStyle = MonoStyle,
            isError = note != null,
            supportingText = note?.let { { Text(it) } },
            trailingIcon = { IconButton(onClick = { scanning = true }) { Icon(Icons.Outlined.QrCodeScanner, "Scan a token QR code") } },
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Add token", enabled = request != null, busy = busy) { request?.let { onAdd(it.tokenAddress) } }
    }
    if (scanning) QrScanner("Point at a token QR code", onResult = { text = it.trim(); scanning = false }, onDismiss = { scanning = false })
}
