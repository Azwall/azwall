package dev.azwall.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Balances from the last session, shown while the PXE catches up, breathe gently until a refresh confirms them
 * (like banking apps): the numbers are readable at once, and visibly not yet current.
 */
@Composable
fun Modifier.stalePulse(active: Boolean): Modifier {
    if (!active) return this
    val alpha by rememberInfiniteTransition(label = "stale").animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "stale-alpha",
    )
    return graphicsLayer { this.alpha = alpha }
}
