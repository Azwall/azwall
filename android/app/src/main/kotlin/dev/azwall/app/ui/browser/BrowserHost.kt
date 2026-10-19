package dev.azwall.app.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.AccountAvatar
import dev.azwall.core.wallet.SessionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.azwall.app.AppViewModel
import dev.azwall.core.wallet.WalletSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MainTab { WALLET, EXPLORE }

enum class MenuItem { WALLET, EXPLORE, SETTINGS, LOCK }

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
 * One browser for the whole unlocked app (its web view survives tab switches), connected to the selected
 * network's dApp gateway: that network's content script, its PXE host. Switching network reconnects it.
 */
@Composable
fun rememberBrowser(session: WalletSession): BrowserController {
    val context = LocalContext.current
    val browser = remember { BrowserController(context) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session) {
        val path = "pxe/${session.network.pxeBundle}/dapp-content-script.js"
        val script = withContext(Dispatchers.IO) { runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull() }
        if (script != null && BrowserController.supported()) browser.attach(session.dapps, script, scope)
    }
    return browser
}

/** The first pending dApp request, docked under the page (the browser is brought to the front first). */
@Composable
fun DappRequests(vm: AppViewModel, onShowPage: () -> Unit) {
    val pending by vm.approvals.pending.collectAsStateWithLifecycle()
    val request = pending.firstOrNull() ?: return
    LaunchedEffect(request.id) { onShowPage() }
    key(request.id) { ApprovalPanel(request) { approved -> vm.approvals.decide(request.id, approved) } }
}
