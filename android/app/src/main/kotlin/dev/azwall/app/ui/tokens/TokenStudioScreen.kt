package dev.azwall.app.ui.tokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.Panel
import dev.azwall.app.ui.home.TokenRow
import dev.azwall.app.ui.sheets.ShareTokenSheet
import dev.azwall.core.wallet.Phase
import dev.azwall.core.wallet.SessionState
import dev.azwall.core.wallet.TokenView

/** What the token studio can ask for. */
data class TokenStudioActions(
    val onBack: () -> Unit,
    val onCreate: (name: String, symbol: String, decimals: Int) -> Unit,
    /** `to` empty: the selected account. */
    val onMint: (token: TokenView, amount: String, private: Boolean, to: String) -> Unit,
)

/**
 * Test networks: create a token whose minter is this account, then mint it to yourself or to anyone, privately or
 * publicly. A token is per Aztec version, like everything on chain; fees are paid by the testnet's sponsor.
 */
@Composable
fun TokenStudioScreen(state: SessionState, busy: Boolean, actions: TokenStudioActions) {
    var minting by rememberSaveable { mutableStateOf<String?>(null) }
    var sharing by rememberSaveable { mutableStateOf<String?>(null) }
    state.tokens.firstOrNull { it.info.address == minting }?.let { token ->
        return MintScreen(token, busy, onBack = { minting = null }) { amount, private, to ->
            actions.onMint(token, amount, private, to)
            minting = null
        }
    }
    state.tokens.firstOrNull { it.info.address == sharing }?.let { ShareTokenSheet(it, state.network.id, state.network.label) { sharing = null } }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Create a token", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // The account shown comes from the last session until the PXE is open: nothing can be sent before.
            if (state.phase != Phase.READY) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.phase == Phase.STARTING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    val text = if (state.phase == Phase.STARTING) "Loading the wallet…" else "The wallet could not load."
                    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            // Unknown until the PXE has read it: the account is loading, not inactive.
            when (state.account?.deployed) {
                null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading the account…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    return@Column
                }
                false -> {
                    Text("Activate the account first: it becomes the token's minter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Column
                }
                true -> Unit
            }
            CreateForm(busy, actions.onCreate)
            val mine = state.tokens.filter { t -> state.created.any { it.equals(t.info.address, ignoreCase = true) } }
            if (mine.isNotEmpty()) Text("Your tokens", style = MaterialTheme.typography.titleMedium)
            mine.forEach { t -> CreatedToken(t, onShare = { sharing = t.info.address }, onMint = { minting = t.info.address }) }
        }
    }
}

@Composable
private fun CreateForm(busy: Boolean, onCreate: (String, String, Int) -> Unit) = Panel {
    var name by rememberSaveable { mutableStateOf("Test Token") }
    var symbol by rememberSaveable { mutableStateOf("TST") }
    var decimals by rememberSaveable { mutableStateOf("6") }
    val places = decimals.toIntOrNull()?.takeIf { it in 0..18 }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it.take(31) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(symbol, { symbol = it.uppercase().take(8) }, label = { Text("Symbol") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(
                decimals, { decimals = it.filter(Char::isDigit).take(2) }, label = { Text("Decimals") }, singleLine = true,
                isError = places == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
        }
        Text(
            "This account becomes the minter. The token exists on this network only; creating it takes one proof.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onCreate(name.trim(), symbol.trim(), places!!) },
            enabled = !busy && name.isNotBlank() && symbol.isNotBlank() && places != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create") }
    }
}

/** A token this account created, as on the home screen, with its two actions: share its QR code, and mint it. */
@Composable
private fun CreatedToken(token: TokenView, onShare: () -> Unit, onMint: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(1f)) { TokenRow(token, showsBalance = false, onClick = onShare) }
    IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share ${token.info.symbol}") }
    IconButton(onClick = onMint) { Icon(Icons.Outlined.AddCircleOutline, "Mint ${token.info.symbol}") }
}
