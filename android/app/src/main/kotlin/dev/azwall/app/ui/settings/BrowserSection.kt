package dev.azwall.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.azwall.core.wallet.SearchEngine

/** Where the Explore address bar searches: only engines that do not profile who searches. */
@Composable
internal fun BrowserSection(engine: SearchEngine, onChoose: (SearchEngine) -> Unit) = Section("Browser") {
    Text("Search engine", color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchEngine.entries.forEach { e -> FilterChip(selected = e == engine, onClick = { onChoose(e) }, label = { Text(e.label) }) }
    }
}
