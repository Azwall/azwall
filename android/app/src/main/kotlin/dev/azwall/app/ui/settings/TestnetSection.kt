package dev.azwall.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
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
import dev.azwall.core.wallet.SessionState

/**
 * Test networks have no faucet for arbitrary tokens: create a token with this account as minter, then mint
 * to yourself, privately or publicly. Fees are paid by the testnet's sponsored fee contract.
 */
@Composable
internal fun TestnetSection(state: SessionState, busy: Boolean, actions: SettingsActions) = Section("Testnet tools") {
    val deployed = state.account?.deployed == true
    if (!deployed) {
        Text("Activate the account first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return@Section
    }
    var name by rememberSaveable { mutableStateOf("Test Token") }
    var symbol by rememberSaveable { mutableStateOf("TST") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(2f))
        OutlinedTextField(symbol, { symbol = it.uppercase().take(6) }, label = { Text("Symbol") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    TextButton(onClick = { actions.onCreateTestToken(name, symbol) }, enabled = !busy && name.isNotBlank() && symbol.isNotBlank()) {
        Text("Create test token")
    }
    val token = state.tokens.firstOrNull() ?: return@Section
    var amount by rememberSaveable { mutableStateOf("1000") }
    var private by rememberSaveable { mutableStateOf(true) }
    Text("Mint ${token.info.symbol} to this account (only works if it is the minter)", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, singleLine = true, modifier = Modifier.weight(1f))
        FilterChip(selected = private, onClick = { private = !private }, label = { Text(if (private) "Private" else "Public") })
    }
    TextButton(onClick = { actions.onMint(token.info.address, amount, private) }, enabled = !busy) { Text("Mint") }
}
