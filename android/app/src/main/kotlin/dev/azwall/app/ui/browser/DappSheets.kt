package dev.azwall.app.ui.browser

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.getValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.hostOf
import dev.azwall.app.ui.siteOf
import dev.azwall.core.wallet.DappSession

val ConnectionsIcon = Icons.Outlined.Link

/** The sites connected to the wallet, each with its emojis, and a way to disconnect them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsSheet(sessions: List<DappSession>, networkLabel: String, onDisconnect: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Connected sites", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                "Sites connect through the wallet-sdk protocol, on $networkLabel. Every transaction still asks you first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (sessions.isEmpty()) Text("No site is connected.", modifier = Modifier.padding(24.dp))
            sessions.forEach { s ->
                ListItem(
                    headlineContent = { Text(siteOf(s.origin)) },
                    supportingContent = { Text("${s.emojis} · ${if (s.granted) "access granted" else "connecting"}") },
                    trailingContent = { TextButton(onClick = { onDisconnect(s.sessionId) }) { Text("Disconnect") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

/** The open tabs: switch to one, close one, or open a new one on the start page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsSheet(tabs: List<BrowserTab>, current: BrowserTab, onSelect: (BrowserTab) -> Unit, onClose: (BrowserTab) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Tabs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            tabs.forEach { tab ->
                val page by tab.page.collectAsStateWithLifecycle()
                ListItem(
                    headlineContent = { Text(if (page.start) "Start page" else page.title.ifBlank { page.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { if (!page.start) Text(hostOf(page.url), maxLines = 1) },
                    trailingContent = { IconButton(onClick = { onClose(tab) }) { Icon(Icons.Outlined.Close, "Close tab") } },
                    colors = ListItemDefaults.colors(containerColor = if (tab == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    modifier = Modifier.clickable { onSelect(tab) },
                )
            }
            TextButton(onClick = onNew, modifier = Modifier.padding(horizontal = 16.dp)) { Text("+ New tab") }
        }
    }
}
