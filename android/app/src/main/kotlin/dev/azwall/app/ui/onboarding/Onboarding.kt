package dev.azwall.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.Panel
import dev.azwall.app.ui.components.PrimaryButton
import dev.azwall.app.ui.components.SecondaryButton
import dev.azwall.app.ui.components.SecureWindow
import dev.azwall.app.ui.groupKey
import dev.azwall.app.ui.theme.MonoStyle
import dev.azwall.app.ui.theme.WalletTheme
import dev.azwall.core.vault.Vault

private enum class Step { WELCOME, BACKUP, IMPORT }

/** First run: create a wallet (and back its key up) or import an existing key. */
@Composable
fun OnboardingFlow(busy: Boolean, onCreate: (signingKey: String, alias: String) -> Unit) {
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    // Not saveable: the saved state may be written to disk, and a new key is as good if the process restarts.
    val newKey = remember { Vault.newSigningKey() }
    when (step) {
        Step.WELCOME -> Welcome(onCreate = { step = Step.BACKUP }, onImport = { step = Step.IMPORT })
        Step.BACKUP -> Backup(newKey, busy, onBack = { step = Step.WELCOME }, onDone = { onCreate(newKey, "Account 1") })
        Step.IMPORT -> Import(busy, onBack = { step = Step.WELCOME }, onImport = { onCreate(it, "Account 1") })
    }
}

@Composable
private fun Screen(onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Box(Modifier.height(56.dp)) {
            onBack?.let { IconButton(onClick = it, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }
        }
        content()
    }
}

@Composable
private fun Welcome(onCreate: () -> Unit, onImport: () -> Unit) = Screen {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Icon(Icons.Outlined.Shield, null, tint = WalletTheme.colors.private, modifier = Modifier.size(56.dp))
        Text("Azwall", style = MaterialTheme.typography.displaySmall)
        Text("A private wallet for Aztec.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Feature(Icons.Outlined.Lock, "Private by default", "Balances and transfers stay encrypted. Only you can see them.")
        Feature(Icons.Outlined.Bolt, "Proven on your phone", "Every transaction is proven on this device, natively, in seconds.")
        Feature(Icons.Outlined.Hub, "Every Aztec network", "Switch between networks running different Aztec versions.")
    }
    PrimaryButton("Create a new wallet", onClick = onCreate)
    Spacer(Modifier.height(12.dp))
    SecondaryButton("I already have a key", onClick = onImport)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Backup(key: String, busy: Boolean, onBack: () -> Unit, onDone: () -> Unit) = Screen(onBack) {
    var saved by rememberSaveable { mutableStateOf(false) }
    SecureWindow()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Back up your key", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This key is your account. Write it down and keep it offline: anyone who has it controls your funds, " +
                "and without it a lost phone means lost funds.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Panel { Text(groupKey(key), style = MonoStyle, modifier = Modifier.padding(20.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text("I wrote my key down somewhere safe")
        }
    }
    PrimaryButton("Create wallet", enabled = saved, busy = busy, onClick = onDone)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Import(busy: Boolean, onBack: () -> Unit, onImport: (String) -> Unit) = Screen(onBack) {
    var input by remember { mutableStateOf("") }
    SecureWindow()
    val error = input.takeIf { it.isNotBlank() }?.let { runCatching { Vault.parseSigningKey(it) }.exceptionOrNull()?.message }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Import your key", style = MaterialTheme.typography.headlineSmall)
        Text("Paste the 64-character signing key of your Aztec account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.trim() },
            label = { Text("Signing key") },
            isError = error != null,
            // A password field: keyboards neither learn nor suggest it.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            supportingText = { error?.let { Text(it) } },
            textStyle = MonoStyle,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    PrimaryButton("Import", enabled = input.isNotBlank() && error == null, busy = busy, onClick = { onImport(input) })
    Spacer(Modifier.height(24.dp))
}

/** Shown when a vault exists: one tap, then biometric or device credential. */
@Composable
fun LockScreen(busy: Boolean, onUnlock: () -> Unit) = Screen {
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Shield, null, tint = WalletTheme.colors.private, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Azwall", style = MaterialTheme.typography.displaySmall)
        Text("Your wallet is locked", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    PrimaryButton("Unlock", busy = busy, onClick = onUnlock)
    Spacer(Modifier.height(24.dp))
}
