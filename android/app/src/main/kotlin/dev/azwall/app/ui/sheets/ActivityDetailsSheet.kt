package dev.azwall.app.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.activityTitle
import dev.azwall.app.ui.components.DetailRow
import dev.azwall.core.wallet.ActivityDetails
import dev.azwall.core.wallet.ActivityItem
import dev.azwall.core.wallet.KnownExplorer
import java.text.DateFormat
import java.util.Date

/** One transaction of the history in full: tapping a row of Activity opens it. Values can be selected and copied. */
@Composable
fun ActivityDetailsSheet(
    item: ActivityItem,
    explorers: List<KnownExplorer>,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) = Sheet(activityTitle(item), onDismiss, fullHeight = true) {
    val clipboard = LocalClipboardManager.current
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.createdAt))
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ActivityDetails.of(item, date).forEach { (label, value) -> DetailRow(label, value) }
        item.txHash?.let { hash ->
            TextButton(onClick = { clipboard.setText(AnnotatedString(hash)) }, modifier = Modifier.fillMaxWidth()) { Text("Copy transaction hash") }
            // Opening it tells that explorer which transaction is looked at: only when the user asks.
            explorers.forEach { explorer ->
                explorer.pageOfTx(hash)?.let { page ->
                    TextButton(onClick = { onOpen(page) }, modifier = Modifier.fillMaxWidth()) { Text("View on ${explorer.name}") }
                }
            }
        }
    }
}
