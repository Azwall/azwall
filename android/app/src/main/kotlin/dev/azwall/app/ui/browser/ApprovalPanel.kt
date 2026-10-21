package dev.azwall.app.ui.browser

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azwall.app.ui.siteOf
import dev.azwall.app.ui.theme.WalletTheme
import dev.azwall.core.wallet.ApprovalKind
import dev.azwall.core.wallet.ApprovalRequest

/**
 * A dApp request, docked under the page instead of over it: the page above stays fully usable (scroll it, read
 * its emojis), and nothing but the two buttons answers the request. The panel collapses to its title and
 * buttons, like an accordion, to give the page back most of the screen.
 */
@Composable
fun ApprovalPanel(request: ApprovalRequest, onDecide: (Boolean) -> Unit) {
    var expanded by rememberSaveable(request.id) { mutableStateOf(true) }
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header(request, expanded) { expanded = !expanded }
            if (expanded) Body(request)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onDecide(false) }, modifier = Modifier.weight(1f).height(48.dp)) { Text(rejectLabel(request.kind)) }
                Button(onClick = { onDecide(true) }, modifier = Modifier.weight(1f).height(48.dp)) { Text(approveLabel(request.kind)) }
            }
        }
    }
}

@Composable
private fun Header(request: ApprovalRequest, expanded: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        // The site as the browser knows it comes first: what a page says about itself is only a detail below.
        Column(Modifier.weight(1f)) {
            Text(siteOf(request.origin), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(request.title, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onToggle) {
            Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, if (expanded) "Collapse" else "Expand")
        }
    }
}

@Composable
private fun Body(request: ApprovalRequest) {
    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        request.emojis?.let { Emojis(it) }
        request.details.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        if (request.kind == ApprovalKind.TRANSACTION) {
            Text("The proof is generated on this phone; the fee is sponsored on testnet.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Emojis(emojis: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(emojis, fontSize = 26.sp, lineHeight = 38.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                "The page above must show these same emojis. If it does not, reject: someone may be in between.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = WalletTheme.colors.pending,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

private fun approveLabel(kind: ApprovalKind) = when (kind) {
    ApprovalKind.CONNECT -> "Connect"
    ApprovalKind.VERIFY -> "They match"
    ApprovalKind.CAPABILITIES -> "Allow"
    ApprovalKind.TRANSACTION -> "Approve and prove"
    ApprovalKind.AUTHWIT -> "Authorize"
}

private fun rejectLabel(kind: ApprovalKind) = if (kind == ApprovalKind.VERIFY) "They differ" else "Reject"
