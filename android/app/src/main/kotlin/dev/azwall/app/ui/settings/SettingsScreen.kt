package dev.azwall.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.Panel
import dev.azwall.app.ui.theme.MonoStyle
import dev.azwall.app.ui.theme.WalletTheme
import dev.azwall.core.NodeCheck
import dev.azwall.core.wallet.SessionState

/** Everything Settings can trigger; the caller wires each to the view model. */
data class SettingsActions(
    val onBack: () -> Unit,
    val onRename: (String) -> Unit,
    val onRevealKey: () -> Unit,
    val onCheckNode: () -> Unit,
    val onLoadProver: () -> Unit,
    val onCreateTestToken: (name: String, symbol: String) -> Unit,
    val onMint: (tokenAddress: String, amount: String, private: Boolean) -> Unit,
    val onLock: () -> Unit,
    val onWipe: () -> Unit,
)

data class SettingsInfo(val appVersion: String, val coreVersion: String, val releases: List<String>, val node: NodeCheck?, val proverReady: Boolean)

@Composable
fun SettingsScreen(state: SessionState, info: SettingsInfo, busy: Boolean, actions: SettingsActions) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AccountSection(state, actions)
            NetworkSection(state, info, actions)
            if (state.network.id.startsWith("testnet")) TestnetSection(state, busy, actions)
            SecuritySection(actions)
            AboutSection(info)
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
internal fun Line(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccountSection(state: SessionState, actions: SettingsActions) = Section("Account") {
    val account = state.account ?: return@Section
    var name by rememberSaveable(account.address) { mutableStateOf(account.alias) }
    OutlinedTextField(
        name,
        { name = it },
        label = { Text("Name") },
        singleLine = true,
        trailingIcon = { TextButton(onClick = { actions.onRename(name) }, enabled = name.isNotBlank() && name != account.alias) { Text("Save") } },
        modifier = Modifier.fillMaxWidth(),
    )
    Line("Activated", if (account.deployed == true) "yes" else "not yet")
    TextButton(onClick = actions.onRevealKey) { Text("Show secret key") }
}

@Composable
private fun NetworkSection(state: SessionState, info: SettingsInfo, actions: SettingsActions) = Section("Network") {
    Line("Network", state.network.label)
    Line("Aztec", state.network.aztecVersion)
    Line("Node", state.check?.nodeVersion ?: "—")
    Line("Block", state.check?.blockNumber?.toString() ?: "—")
    Line("RPC", state.network.rpcHost)
    info.node?.let { node ->
        val c = WalletTheme.colors
        Text(
            if (node.compatible) "Node compatible" else "Node incompatible: ${node.mismatches.joinToString()}",
            color = if (node.compatible) c.success else c.danger,
        )
    }
    TextButton(onClick = actions.onCheckNode) { Text("Check node") }
    Line("Prover", "Barretenberg ${state.network.barretenbergVersion}")
    Line("Prover loaded", if (info.proverReady) "yes" else "on first proof")
    TextButton(onClick = actions.onLoadProver) { Text("Load prover now") }
}

@Composable
private fun SecuritySection(actions: SettingsActions) = Section("Security") {
    var confirmWipe by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = actions.onLock) { Text("Lock wallet") }
    TextButton(onClick = { confirmWipe = true }) { Text("Remove wallet from this device", color = WalletTheme.colors.danger) }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Remove this wallet?") },
            text = { Text("Your keys are deleted from this phone. Without your key backup, the funds are lost for good.") },
            confirmButton = { TextButton(onClick = { confirmWipe = false; actions.onWipe() }) { Text("Remove", color = WalletTheme.colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AboutSection(info: SettingsInfo) = Section("About") {
    Line("Azwall", info.appVersion)
    Line("Rust core", info.coreVersion)
    Line("Provers", info.releases.joinToString(", "))
}

/** Shows a revealed signing key once, until dismissed. */
@Composable
fun SecretKeyDialog(key: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Secret key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Anyone with this key controls the account. Never share it.", color = WalletTheme.colors.danger)
                Text(key.chunked(16).joinToString("\n"), style = MonoStyle)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onCopy) { Text("Copy") } },
    )
}
