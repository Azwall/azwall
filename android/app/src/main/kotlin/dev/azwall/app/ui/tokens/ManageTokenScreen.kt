package dev.azwall.app.ui.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.Panel
import dev.azwall.app.ui.components.PrimaryButton
import dev.azwall.app.ui.send.Header
import dev.azwall.app.ui.sheets.TokenShareCard
import dev.azwall.core.wallet.Amounts
import dev.azwall.core.wallet.TokenView

/** One token this account created: what it holds of it, the QR code for others to add it, and minting. */
@Composable
fun ManageTokenScreen(token: TokenView, network: String, networkLabel: String, busy: Boolean, onBack: () -> Unit, onMint: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Header(token.info.name, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Holdings(token)
            TokenShareCard(token, network, networkLabel)
        }
        PrimaryButton("Mint ${token.info.symbol}", enabled = !busy, onClick = onMint)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Holdings(token: TokenView) = Panel {
    val d = token.info.decimals
    val balance = token.balance
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Line("Private", balance?.let { Amounts.display(it.privateAmount, d, 4) } ?: "…")
        Line("Public", balance?.let { Amounts.display(it.publicAmount, d, 4) } ?: "…")
        Line("Decimals", "$d")
        Text("You are its minter: only this account can create more.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Line(label: String, value: String) = Row(Modifier.fillMaxWidth()) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    Text(value, style = MaterialTheme.typography.titleSmall)
}
