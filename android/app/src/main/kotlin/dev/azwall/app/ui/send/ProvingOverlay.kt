package dev.azwall.app.ui.send

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.provingTitle
import dev.azwall.app.ui.theme.WalletTheme
import dev.azwall.core.wallet.ProvingJob
import kotlinx.coroutines.delay

/**
 * Full screen while a transaction is proven on the device: the wait is the price of privacy, so the app
 * says what is happening and how long it usually takes.
 */
@Composable
fun ProvingOverlay(job: ProvingJob) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(job) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val seconds = ((now - job.startedAt) / 1000).coerceAtLeast(0)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = 0.97f)) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Spinner()
            Text("${provingTitle(job.item.kind)}…", style = MaterialTheme.typography.titleLarge)
            Text(
                "Generating a zero-knowledge proof on this phone. Nothing leaves the device until the proof is ready.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("$seconds s · usually 15 to 30 s", style = MaterialTheme.typography.labelLarge, color = WalletTheme.colors.private)
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
        Icon(Icons.Outlined.Shield, null, tint = color, modifier = Modifier.size(36.dp))
    }
}
