package dev.azwall.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.AccountAvatar
import dev.azwall.app.ui.components.CircleAction
import dev.azwall.app.ui.components.CopyAddress
import dev.azwall.app.ui.sheets.ActivityDetailsSheet
import dev.azwall.app.ui.sheets.ReviewSheet
import dev.azwall.app.ui.sheets.ShareTokenSheet
import dev.azwall.app.ui.theme.WalletTheme
import dev.azwall.core.pxe.FoundToken
import dev.azwall.core.wallet.ActivityItem
import dev.azwall.core.wallet.Amounts
import dev.azwall.core.wallet.FeeJuice
import dev.azwall.core.wallet.Fees
import dev.azwall.core.wallet.Phase
import dev.azwall.core.wallet.Review
import dev.azwall.core.wallet.SessionState
import dev.azwall.core.wallet.TokenView

/** What the home screen can ask for; the caller routes each to a screen or sheet. */
data class HomeActions(
    val onNetworks: () -> Unit,
    val onAccounts: () -> Unit,
    val onMenu: () -> Unit,
    val onSettings: () -> Unit,
    val onSend: () -> Unit,
    val onReceive: () -> Unit,
    val onShield: () -> Unit,
    val onAddToken: () -> Unit,
    val onFollowFound: (FoundToken) -> Unit,
    val onChoosePrimary: (String) -> Unit,
    val onDismissFound: (FoundToken) -> Unit,
    val onActivate: () -> Unit,
    val onAcknowledgeReset: () -> Unit,
    val onRemoveToken: (address: String) -> Unit,
    val onRefresh: () -> Unit,
    val onCopied: () -> Unit,
    /** Claims Fee Juice bridged to the active account (SessionState.claimable). */
    val onClaimFeeJuice: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: SessionState, nodeOk: Boolean?, actions: HomeActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var shared by remember { mutableStateOf<TokenView?>(null) }
    // By id: the sheet follows the transaction while it moves from proving to a block.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar(state, nodeOk, actions)
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (state.chainReset) item { ChainResetNotice(state.network.label, actions.onAcknowledgeReset) }
                item { AccountHeader(state, actions) }
                item { ActionRow(state, actions) }
                item {
                    SecondaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Tokens") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Activity") })
                    }
                }
                item { FoundTokens(state.found, actions.onFollowFound, actions.onDismissFound) }
                if (tab == 0) tokenItems(state, actions) { shared = it } else activityItems(state) { opened = it.id }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
        StatusLine(state)
    }
    shared?.let { token ->
        ShareTokenSheet(token, state.network.id, state.network.label, onRemove = { actions.onRemoveToken(token.info.address) }) { shared = null }
    }
    state.activity.firstOrNull { it.id == opened }?.let { ActivityDetailsSheet(it) { opened = null } }
}

@Composable
private fun TopBar(state: SessionState, nodeOk: Boolean?, actions: HomeActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onMenu) { Icon(Icons.Outlined.Menu, "Menu") }
        Spacer(Modifier.weight(1f))
        NetworkPill(state.network.label, healthy = nodeOk ?: (state.phase == Phase.READY), onClick = actions.onNetworks)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = actions.onReceive) { Icon(Icons.Outlined.QrCode2, "Receive") }
    }
}

@Composable
fun NetworkPill(label: String, healthy: Boolean, onClick: () -> Unit) {
    val dot = if (healthy) WalletTheme.colors.success else WalletTheme.colors.pending
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.size(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AccountHeader(state: SessionState, actions: HomeActions) {
    val account = state.account
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = actions.onAccounts).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AccountAvatar(account?.address ?: "0x0", size = 28.dp)
            Text(account?.alias ?: "…", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.KeyboardArrowDown, "Accounts", modifier = Modifier.size(18.dp))
        }
        account?.let { CopyAddress(it.address, Modifier.padding(top = 6.dp), onCopied = actions.onCopied) }
        Spacer(Modifier.height(20.dp))
        // Activating pays a fee where there is no sponsor: confirmed like any transaction first.
        var reviewing by rememberSaveable { mutableStateOf(false) }
        var claiming by rememberSaveable { mutableStateOf(false) }
        BalanceHero(state, actions.copy(onActivate = { reviewing = true }, onClaimFeeJuice = { claiming = true }))
        val claim = state.claimable
        if (claiming && claim != null) {
            val amount = "${Amounts.display(claim.units, FeeJuice.INFO.decimals, 4)} FJ"
            ReviewSheet(Review.claimFeeJuice(amount, state.network.label), onDismiss = { claiming = false }) {
                claiming = false
                actions.onClaimFeeJuice()
            }
        }
        if (reviewing) {
            val fee = state.claimable?.let(Fees::claimLine) ?: Fees.line(state.check, state.feeJuice)
            ReviewSheet(Review.activation(state.network.label, fee), onDismiss = { reviewing = false }) {
                reviewing = false
                actions.onActivate()
            }
        }
    }
}

@Composable
private fun ActionRow(state: SessionState, actions: HomeActions) {
    val ready = state.phase == Phase.READY && state.account?.deployed == true
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        CircleAction(Icons.AutoMirrored.Outlined.CallMade, "Send", enabled = ready && state.tokens.isNotEmpty(), onClick = actions.onSend)
        CircleAction(Icons.Outlined.QrCode2, "Receive", enabled = state.account != null, onClick = actions.onReceive)
        CircleAction(Icons.Outlined.Shield, "Shield", enabled = ready && state.tokens.isNotEmpty(), onClick = actions.onShield)
        CircleAction(Icons.Outlined.Add, "Token", enabled = state.phase == Phase.READY, onClick = actions.onAddToken)
    }
}

private fun LazyListScope.tokenItems(state: SessionState, actions: HomeActions, onShare: (TokenView) -> Unit) {
    // Fee Juice cannot be sent or shared: its row only shows the balance.
    item(key = FeeJuice.ID) { TokenRow(state.feeJuiceToken, state.stale) }
    if (state.tokens.isEmpty() && state.found.isEmpty()) {
        item { EmptyHint("No tokens yet", "Add a token by its address to see its private and public balances.", "Add a token", actions.onAddToken) }
    }
    items(state.tokens, key = { it.info.address }) { TokenRow(it, state.stale, onClick = { onShare(it) }) }
}

private fun LazyListScope.activityItems(state: SessionState, onOpen: (ActivityItem) -> Unit) {
    if (state.activity.isEmpty()) item { EmptyHint("No activity yet", "Transactions you make from this account, here or in dApps, appear here.") }
    items(state.activity, key = { it.id }) { ActivityRow(it, onClick = { onOpen(it) }) }
}
