package dev.azwall.app.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.azwall.app.AppViewModel
import dev.azwall.app.Auth
import dev.azwall.app.ui.components.AccountAvatar
import dev.azwall.core.DiagnosticLog
import dev.azwall.core.wallet.DappApprovals
import dev.azwall.core.wallet.SessionState
import dev.azwall.core.wallet.WalletSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab { WALLET, EXPLORE }

enum class MenuItem { WALLET, EXPLORE, TOKENS, SETTINGS, LOCK }

/** The side menu behind the top-left button, as in MetaMask mobile: who and where you are, then where to go. */
@Composable
fun AppDrawer(state: SessionState, tab: MainTab, onSelect: (MenuItem) -> Unit) {
    ModalDrawerSheet {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.account?.let { AccountAvatar(it.address, size = 48.dp) }
            Text(state.account?.alias ?: "", style = MaterialTheme.typography.titleMedium)
            Text(state.network.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Item(Icons.Outlined.AccountBalanceWallet, "Wallet", tab == MainTab.WALLET) { onSelect(MenuItem.WALLET) }
        Item(Icons.Outlined.Explore, "Explore", tab == MainTab.EXPLORE) { onSelect(MenuItem.EXPLORE) }
        // Test networks only: there is no faucet for arbitrary tokens, so the wallet makes its own.
        if (state.network.id.startsWith("testnet")) Item(Icons.Outlined.MonetizationOn, "Create a token", false) { onSelect(MenuItem.TOKENS) }
        Item(Icons.Outlined.Settings, "Settings", false) { onSelect(MenuItem.SETTINGS) }
        Item(Icons.Outlined.Lock, "Lock wallet", false) { onSelect(MenuItem.LOCK) }
    }
}

@Composable
private fun Item(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null) },
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/**
 * One browser for the whole unlocked app (its tabs survive tab switches), connected to the selected network's dApp
 * gateway: that network's content script, its PXE host. Switching network reconnects it. When a page goes away
 * (navigation, closed tab), its pending requests are rejected and its sessions closed.
 */
@Composable
fun rememberBrowser(session: WalletSession, approvals: DappApprovals, onProblem: (String) -> Unit = {}): BrowserTabs {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(session)
    val browser = remember {
        BrowserTabs(context) { page ->
            approvals.rejectTab(page)
            // A page the gateway did not forget keeps its dApp session until the PXE restarts; reported.
            scope.launch { runCatching { current.dapps.closePage(page) }.onFailure { DiagnosticLog.shared.report("dapps", it) } }
        }
    }
    LaunchedEffect(session) {
        val path = "pxe/${session.network.pxeBundle}/dapp-content-script.js"
        // Without its content script the browser still shows pages, but no dApp can reach the wallet.
        val script = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.onFailure { DiagnosticLog.shared.report("browser", it) }.getOrNull()
        }
        // A build without it: sites open, but no dApp can reach the wallet, which the user must know.
        if (script == null) onProblem("Apps cannot connect to the wallet on ${session.network.label}: this version of Azwall lacks the connection script for it. Update the app.")
        if (script != null && BrowserTabs.supported()) browser.attach(session.dapps, script, scope)
    }
    return browser
}

/** The first pending dApp request, docked under the page (its tab is brought to the front first). */
@Composable
fun DappRequests(vm: AppViewModel, browser: BrowserTabs, owner: Auth, settle: suspend () -> Unit, onShowPage: () -> Unit) {
    val pending by vm.approvals.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val request = pending.firstOrNull() ?: return
    LaunchedEffect(request.id) {
        browser.selectPage(request.tab)
        onShowPage()
    }
    key(request.id) {
        ApprovalPanel(request) { approved ->
            scope.launch { vm.approvals.answer(request, approved, owner, settle) }
        }
    }
}
