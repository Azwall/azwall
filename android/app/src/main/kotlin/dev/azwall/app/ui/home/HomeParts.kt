package dev.azwall.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.activityAmount
import dev.azwall.app.ui.activityTitle
import dev.azwall.app.ui.components.Panel
import dev.azwall.app.ui.components.StatusChip
import dev.azwall.app.ui.components.stalePulse
import dev.azwall.app.ui.components.TokenBadge
import dev.azwall.app.ui.friendlyError
import dev.azwall.app.ui.shortAddress
import dev.azwall.app.ui.siteOf
import dev.azwall.app.ui.theme.WalletTheme
import dev.azwall.app.ui.timeAgo
import dev.azwall.core.wallet.ActivityItem
import dev.azwall.core.wallet.ActivityKind
import dev.azwall.core.wallet.ActivityStatus
import dev.azwall.core.wallet.Amounts
import dev.azwall.core.wallet.Phase
import dev.azwall.core.wallet.SessionState
import dev.azwall.core.wallet.TokenView
import java.math.BigInteger

/** The big number: the hero token's total (SessionState.hero), split into private and public like a shielded wallet does. */
@Composable
internal fun BalanceHero(state: SessionState, actions: HomeActions) {
    val account = state.account
    when {
        // With balances from the last session, show them (stale) rather than a spinner while the PXE starts.
        state.phase == Phase.STARTING && !state.stale -> Starting(state.network.label)
        state.phase == Phase.FAILED -> Notice("Can't reach ${state.network.label}", friendlyError(state.error), "Retry", actions.onRefresh)
        account?.deployed == false -> Notice(
            "Activate your account",
            "Your address can already receive funds. Activating publishes your account contract; fees are sponsored on testnet.",
            "Activate",
            actions.onActivate,
            Icons.Outlined.RocketLaunch,
        )
        state.tokens.isEmpty() -> Notice(
            "Your account is ready",
            "Add a token to see its private and public balances. On testnet, Create a token in the menu makes one for you.",
            "Add a token",
            actions.onAddToken,
        )
        else -> TokenHero(state.hero ?: state.tokens.first(), state.tokens, state.stale, actions)
    }
}

@Composable
private fun Starting(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Connecting to $label…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TokenHero(token: TokenView, tokens: List<TokenView>, stale: Boolean, actions: HomeActions) {
    val balance = token.balance
    val priv = balance?.privateAmount ?: BigInteger.ZERO
    val pub = balance?.publicAmount ?: BigInteger.ZERO
    val decimals = token.info.decimals
    Column(Modifier.stalePulse(stale), horizontalAlignment = Alignment.CenterHorizontally) {
        HeroAmount(if (balance == null) "…" else "${Amounts.display(priv + pub, decimals, 4)} ${token.info.symbol}", tokens, actions.onChoosePrimary)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Split(Icons.Outlined.Lock, "Private", Amounts.display(priv, decimals, 4), WalletTheme.colors.private)
            Split(Icons.Outlined.Public, "Public", Amounts.display(pub, decimals, 4), WalletTheme.colors.public)
        }
        if (pub.signum() > 0) {
            TextButton(onClick = actions.onShield) { Text("Make ${Amounts.display(pub, decimals, 4)} ${token.info.symbol} private") }
        }
    }
}

@Composable
private fun Split(icon: ImageVector, label: String, amount: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, label, tint = color, modifier = Modifier.size(16.dp))
        Text("$label $amount", style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun Notice(title: String, body: String, action: String, onAction: () -> Unit, icon: ImageVector? = null) {
    Panel(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.primary) }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
internal fun TokenRow(token: TokenView, stale: Boolean = false) {
    val decimals = token.info.decimals
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenBadge(token.info.symbol, token.info.address)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(token.info.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(token.info.symbol, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val balance = token.balance
        Column(Modifier.stalePulse(stale), horizontalAlignment = Alignment.End) {
            if (balance == null) {
                Text("…")
            } else {
                Text(Amounts.display(balance.privateAmount + balance.publicAmount, decimals, 4), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${Amounts.display(balance.privateAmount, decimals, 2)} private · ${Amounts.display(balance.publicAmount, decimals, 2)} public",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ActivityRow(item: ActivityItem) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(activityTitle(item), style = MaterialTheme.typography.titleSmall)
            val detail = listOfNotNull(
                timeAgo(item.createdAt),
                item.origin?.let { "via " + siteOf(it) },
                item.counterparty?.let { (if (item.kind == ActivityKind.RECEIVE) "from " else "to ") + shortAddress(it) },
                item.blockNumber?.let { "block $it" },
            )
            Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.status == ActivityStatus.FAILED) {
                Text(friendlyError(item.error), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.danger, maxLines = 2)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            activityAmount(item)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            StatusChip(item.status)
        }
    }
}

@Composable
internal fun EmptyHint(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { TextButton(onClick = onAction) { Text(it) } }
    }
}

/** The hero amount; with several tokens, tapping it picks which one the top of the screen shows. */
@Composable
private fun HeroAmount(text: String, tokens: List<TokenView>, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = tokens.size > 1) { open = true }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.displaySmall)
            if (tokens.size > 1) Icon(Icons.Outlined.KeyboardArrowDown, "Choose the token shown here")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tokens.forEach { t ->
                DropdownMenuItem(text = { Text("${t.info.name} (${t.info.symbol})") }, onClick = { onChoose(t.info.address); open = false })
            }
        }
    }
}
