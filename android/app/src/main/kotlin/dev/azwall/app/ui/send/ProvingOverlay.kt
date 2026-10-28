package dev.azwall.app.ui.send

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.azwall.app.R
import dev.azwall.app.ui.provingTitle
import dev.azwall.app.ui.theme.WalletTheme
import dev.azwall.core.wallet.ProofGate
import dev.azwall.core.wallet.ProvingJob
import kotlinx.coroutines.delay

/**
 * Full screen while a transaction is proven on the device: the wait is the price of privacy, so the app
 * says what is happening and how long it usually takes.
 */
@Composable
fun ProvingOverlay(job: ProvingJob, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var cancelling by remember(job) { mutableStateOf(false) }
    // Once the proof is handed over, the transaction may reach the network at any moment: no cancel promises otherwise.
    val sending by ProofGate.released.collectAsState()
    // The overlay covers the app while proving: the system back gesture cancels, like the button.
    BackHandler(enabled = !cancelling && !sending) { cancelling = true; onCancel() }
    LaunchedEffect(job) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val seconds = ((now - job.startedAt) / 1000).coerceAtLeast(0)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Spinner()
            Text(if (sending) "Sending to the network…" else "${provingTitle(job.item.kind)}…", style = MaterialTheme.typography.titleLarge)
            Text(
                if (sending) {
                    "The proof is ready and the transaction is on its way. It can no longer be cancelled; the activity follows it."
                } else {
                    "Generating a zero-knowledge proof on this phone. Nothing leaves the device until the proof is ready."
                },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("$seconds s · usually 15 to 30 s", style = MaterialTheme.typography.labelLarge, color = WalletTheme.colors.private)
            // A proof cannot be stopped midway: it is dropped once ready, and nothing is sent. Once released, it is too late.
            if (!sending) {
                OutlinedButton(onClick = { cancelling = true; onCancel() }, enabled = !cancelling) {
                    Text(if (cancelling) "Cancelling, nothing will be sent…" else "Cancel")
                }
            }
        }
    }
}

@Composable
private fun Spinner() {
    val color = WalletTheme.colors.private
    val angle by rememberInfiniteTransition(label = "proof").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(color.copy(alpha = 0.15f), 0f, 360f, false, style = Stroke(6.dp.toPx()))
            drawArc(color, angle, 100f, false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
        }
        Image(painterResource(R.drawable.azwall_logo), contentDescription = null, modifier = Modifier.size(52.dp))
    }
}
