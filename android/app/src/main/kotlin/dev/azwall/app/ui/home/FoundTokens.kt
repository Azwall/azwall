package dev.azwall.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.Panel
import dev.azwall.app.ui.components.TokenBadge
import dev.azwall.core.pxe.FoundToken
import dev.azwall.core.wallet.Amounts

/**
 * Tokens the account holds that the wallet does not follow yet, found among the contracts dApps registered:
 * one tap adds each to the token list, or dismisses it for good.
 */
@Composable
fun FoundTokens(found: List<FoundToken>, onFollow: (FoundToken) -> Unit, onDismiss: (FoundToken) -> Unit) {
    if (found.isEmpty()) return
    Panel(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text("Found in your apps", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                "Tokens you hold from dApps that the wallet does not show yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            found.forEach { FoundRow(it, onFollow, onDismiss) }
        }
    }
}

@Composable
private fun FoundRow(token: FoundToken, onFollow: (FoundToken) -> Unit, onDismiss: (FoundToken) -> Unit) {
    val b = token.balance
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenBadge(token.symbol, token.address)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${token.name} (${token.symbol})", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Amounts.format(b.privateAmount, token.decimals, 2)} private · ${Amounts.format(b.publicAmount, token.decimals, 2)} public",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = { onFollow(token) }) { Text("Add") }
        IconButton(onClick = { onDismiss(token) }) { Icon(Icons.Outlined.Close, "Not interested") }
    }
}
