package dev.azwall.app.ui.browser

import androidx.compose.runtime.key
import androidx.compose.foundation.border
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.azwall.app.ui.theme.WalletTheme
import dev.azwall.core.NetworkInfo
import dev.azwall.core.wallet.DappSession
import dev.azwall.core.wallet.SearchEngine
import kotlinx.coroutines.delay

/** The Explore tab: tabs on a start page of the network's dApps, then any website, with dApps able to connect. */
@Composable
fun BrowserScreen(
    browser: BrowserTabs,
    network: NetworkInfo,
    sessions: List<DappSession>,
    engine: SearchEngine,
    onMenu: () -> Unit,
    onConnections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tab by browser.current.collectAsStateWithLifecycle()
    val tabs by browser.tabs.collectAsStateWithLifecycle()
    val page by tab.page.collectAsStateWithLifecycle()
    var switching by remember { mutableStateOf(false) }
    val open = { input: String -> tab.open(input, engine) }
    BackHandler(enabled = !page.start) { tab.back() }
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        AddressBar(if (page.start) "" else page.url, sessions.size, onMenu = onMenu, onOpen = open, onConnections = onConnections)
        if (page.start) {
            Box(Modifier.weight(1f)) { StartPage(network.dapps, network.explorers, sessions, engine, open) }
        } else {
            if (page.progress < 100) LinearProgressIndicator(progress = { page.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            key(tab.id) {
                AndroidView(
                    factory = { tab.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
        NavigationRow(page, tab, tabs.size) { switching = true }
    }
    if (switching) {
        TabsSheet(
            tabs,
            tab,
            onSelect = { browser.select(it); switching = false },
            onClose = browser::close,
            onNew = { browser.open(); switching = false },
            onDismiss = { switching = false },
        )
    }
}

@Composable
private fun AddressBar(url: String, connections: Int, onMenu: () -> Unit, onOpen: (String) -> Unit, onConnections: () -> Unit) {
    var text by remember(url) { mutableStateOf(TextFieldValue(url)) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Like a browser: focusing the bar selects the address, so typing replaces it. Done after the focus
    // event, because the tap that focused the field places the cursor afterwards.
    LaunchedEffect(focused) {
        if (focused) {
            delay(50)
            text = text.copy(selection = TextRange(0, text.text.length))
        }
    }
    val go = {
        onOpen(text.text)
        keyboard?.hide()
        focus.clearFocus()
    }
    Row(Modifier.fillMaxWidth().padding(end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Menu") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text("Search or enter an address") },
            leadingIcon = { if (url.startsWith("https://")) Icon(Icons.Outlined.Lock, "Secure", Modifier.size(16.dp)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            shape = RoundedCornerShape(50),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            interactionSource = interaction,
            // A hardware Enter opens the address too.
            modifier = Modifier.weight(1f).onPreviewKeyEvent { e -> (e.key == Key.Enter && e.type == KeyEventType.KeyUp).also { if (it) go() } },
        )
        IconButton(onClick = onConnections) {
            BadgedBox(badge = { if (connections > 0) Badge(containerColor = WalletTheme.colors.success) { Text("$connections") } }) {
                Icon(dev.azwall.app.ui.browser.ConnectionsIcon, "Connected sites")
            }
        }
    }
}

@Composable
private fun NavigationRow(page: PageState, tab: BrowserTab, tabCount: Int, onTabs: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = tab::back, enabled = !page.start) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        IconButton(onClick = tab::forward, enabled = page.canGoForward || (page.start && page.url.isNotEmpty())) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Forward") }
        IconButton(onClick = tab::home, enabled = !page.start) { Icon(Icons.Outlined.Home, "Home") }
        IconButton(onClick = tab::reload, enabled = !page.start) { Icon(Icons.Outlined.Refresh, "Reload") }
        Text(if (page.start) "" else page.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
        // Like a mobile browser: the number of open tabs in a square, which opens the tab list.
        IconButton(onClick = onTabs) {
            Box(Modifier.size(22.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                Text("$tabCount", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
