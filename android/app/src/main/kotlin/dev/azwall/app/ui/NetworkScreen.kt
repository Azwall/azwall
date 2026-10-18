package dev.azwall.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.azwall.app.Status
import dev.azwall.app.WalletUiState
import dev.azwall.core.NetworkInfo
import dev.azwall.core.NodeCheck

@Composable
fun NetworkScreen(
    state: WalletUiState,
    onSelect: (String) -> Unit,
    onPrepareProver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Azwall", style = MaterialTheme.typography.headlineMedium)
        state.startupError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            return@Column
        }
        Text("Network", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.networks, key = { it.id }) { network ->
                NetworkRow(network, selected = network.id == state.selectedId, onClick = { onSelect(network.id) })
            }
        }
        state.selected?.let { StatusCard(it, state.node, state.prover, onPrepareProver) }
    }
}

@Composable
private fun NetworkRow(network: NetworkInfo, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(network.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Aztec ${network.aztecVersion} · ${network.rpcHost}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusCard(
    network: NetworkInfo,
    node: Status<NodeCheck>,
    prover: Status<String>,
    onPrepareProver: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Node", style = MaterialTheme.typography.titleSmall)
            Text(nodeText(node))
            Text("Prover · Barretenberg ${network.barretenbergVersion}", style = MaterialTheme.typography.titleSmall)
            Text(proverText(network, prover))
            Button(onClick = onPrepareProver, enabled = network.proverSupported && prover !is Status.Running) {
                Text("Load prover")
            }
        }
    }
}

private fun nodeText(node: Status<NodeCheck>): String = when (node) {
    Status.Idle -> "Not checked"
    Status.Running -> "Checking…"
    is Status.Failed -> "Unreachable: ${node.message}"
    is Status.Done -> with(node.value) {
        if (compatible) "Compatible (node $nodeVersion)" else "Incompatible: ${mismatches.joinToString()}"
    }
}

private fun proverText(network: NetworkInfo, prover: Status<String>): String = when {
    !network.proverSupported -> "Not supported by this build"
    prover is Status.Running -> "Loading…"
    prover is Status.Done -> "Ready"
    prover is Status.Failed -> prover.message
    else -> "Not loaded"
}
