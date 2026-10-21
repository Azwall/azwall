package dev.azwall.app.ui.browser

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.Panel
import dev.azwall.app.ui.components.TokenBadge
import dev.azwall.app.ui.siteOf
import dev.azwall.core.wallet.DappSession
import dev.azwall.core.wallet.KnownDapp
import dev.azwall.core.wallet.SearchEngine

/**
 * The browser's start page, native rather than a web page: it shows at once and asks nothing of any server until
 * the user picks a site. Tiles are the network's suggested dApps (a dApp works with one Aztec version), then the
 * sites connected to the wallet. Badges are drawn, not favicons: loading those would tell each site the app opened.
 */
@Composable
fun StartPage(dapps: List<KnownDapp>, sessions: List<DappSession>, engine: SearchEngine, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Explore", style = MaterialTheme.typography.headlineSmall)
        dapps.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { DappTile(it, Modifier.weight(1f)) { onOpen(it.url) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (sessions.isNotEmpty()) {
            Text("Connected sites", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            sessions.distinctBy { it.origin }.forEach { s -> ConnectedSite(s) { onOpen(s.origin) } }
        }
        Text(
            "Searches from the address bar go to ${engine.label}. Change it in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun DappTile(dapp: KnownDapp, modifier: Modifier, onClick: () -> Unit) = Panel(modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick)) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DappIcon(dapp)
        Text(dapp.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(dapp.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 2)
        Text(dapp.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun ConnectedSite(session: DappSession, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 8.dp)) {
        DappIcon(KnownDapp(siteOf(session.origin), session.origin), 32.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(siteOf(session.origin), style = MaterialTheme.typography.titleSmall)
            Text(session.emojis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The site's icon shipped in the app's assets, or a drawn badge for sites without one. */
@Composable
private fun DappIcon(dapp: KnownDapp, size: Dp = 40.dp) {
    val context = LocalContext.current
    val icon = remember(dapp.iconPath) {
        runCatching { context.assets.open(dapp.iconPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }
    if (icon == null) TokenBadge(dapp.name, dapp.tint, size) else Image(icon, null, Modifier.size(size).clip(RoundedCornerShape(10.dp)))
}
