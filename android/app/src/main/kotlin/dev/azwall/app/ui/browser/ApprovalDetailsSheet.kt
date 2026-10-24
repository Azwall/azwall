package dev.azwall.app.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.DetailRow
import dev.azwall.app.ui.sheets.Sheet
import dev.azwall.app.ui.siteOf
import dev.azwall.core.wallet.ApprovalRequest
import dev.azwall.core.wallet.ApprovalSection

/**
 * A dApp request in full, opened from its panel: every contract and function a site asks for, every call and
 * argument of a transaction, its fee and attachments, and the raw request (folded: it is long). Values are whole
 * and can be selected and copied. The request can be answered from here too.
 */
@Composable
fun ApprovalDetailsSheet(request: ApprovalRequest, onDecide: (Boolean) -> Unit, onDismiss: () -> Unit) =
    Sheet(request.title, onDismiss, fullHeight = true) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(siteOf(request.origin), style = MaterialTheme.typography.titleMedium)
            request.sections.forEach { section ->
                if (section.title == RAW_TITLE) RawSection(section) else SectionCard(section)
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onDecide(false) }, modifier = Modifier.weight(1f).height(48.dp)) { Text(rejectLabel(request.kind)) }
            Button(onClick = { onDecide(true) }, modifier = Modifier.weight(1f).height(48.dp)) { Text(approveLabel(request.kind)) }
        }
    }

private const val RAW_TITLE = "Raw request"

@Composable
private fun SectionCard(section: ApprovalSection) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleSmall)
            Rows(section.rows)
        }
    }
}

/** The request as sent: long, so folded until asked for. */
@Composable
private fun RawSection(section: ApprovalSection) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(section.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Fold" else "Unfold")
            }
            if (open) Rows(section.rows)
        }
    }
}

@Composable
private fun Rows(rows: List<ApprovalSection.Row>) {
    rows.forEach { DetailRow(it.label, it.value) }
}
