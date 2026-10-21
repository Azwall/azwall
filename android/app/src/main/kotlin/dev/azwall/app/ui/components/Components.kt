package dev.azwall.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.shortAddress
import dev.azwall.app.ui.theme.MonoStyle
import dev.azwall.app.ui.theme.WalletTheme
import dev.azwall.core.wallet.ActivityStatus

/** Deterministic avatar for an address: two hues and an angle derived from its bytes (like MetaMask's). */
@Composable
fun AccountAvatar(address: String, size: Dp = 36.dp) {
    val seed = address.removePrefix("0x").ifEmpty { "0" }
    val h1 = (seed.take(6).toLong(16) % 360).toFloat()
    val h2 = (h1 + 90 + (seed.takeLast(4).toLong(16) % 120)) % 360
    val brush = Brush.linearGradient(listOf(Color.hsv(h1, 0.55f, 0.95f), Color.hsv(h2, 0.65f, 0.75f)))
    Canvas(Modifier.size(size).clip(CircleShape)) {
        drawRect(brush)
        drawCircle(Color.White.copy(alpha = 0.18f), radius = this.size.minDimension * 0.32f, center = Offset(this.size.width * 0.3f, this.size.height * 0.72f))
    }
}

/** Token badge: the symbol's first letters on a color derived from the token address. */
@Composable
fun TokenBadge(symbol: String, address: String, size: Dp = 40.dp) {
    val hue = (address.removePrefix("0x").takeLast(6).ifEmpty { "0" }.toLong(16) % 360).toFloat()
    Box(Modifier.size(size).clip(CircleShape).background(Color.hsv(hue, 0.45f, 0.85f)), contentAlignment = Alignment.Center) {
        Text(symbol.take(3).uppercase(), color = Color(0xFF16131F), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatusChip(status: ActivityStatus) {
    val c = WalletTheme.colors
    val (label, color) = when (status) {
        ActivityStatus.PROVING -> "Proving" to c.private
        ActivityStatus.PENDING -> "Pending" to c.pending
        ActivityStatus.CONFIRMED -> "Confirmed" to c.success
        ActivityStatus.FAILED -> "Failed" to c.danger
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Round action under the balance (Send, Receive, Shield…). */
@Composable
fun CircleAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    // Unavailable: the same button at half opacity. Grey ones looked like another kind of button, still usable.
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).alpha(if (enabled) 1f else 0.5f)) {
        Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

/**
 * Private or public, as one switch: a closed padlock named "Private" when on, an open one named "Public" when off.
 * `labels` names the two states (shielding says "Make private" / "Make public").
 */
@Composable
fun PrivacySwitch(private: Boolean, onChange: (Boolean) -> Unit, labels: Pair<String, String> = "Private" to "Public") {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .toggleable(value = private, role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (private) Icons.Outlined.Lock else Icons.Outlined.LockOpen, contentDescription = null)
        Text(if (private) labels.first else labels.second, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = private, onCheckedChange = null)
    }
}

/** A short address that copies the full one when tapped. */
@Composable
fun CopyAddress(address: String, modifier: Modifier = Modifier, onCopied: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier.clip(RoundedCornerShape(50)).clickable { clipboard.setText(AnnotatedString(address)); onCopied() }
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(shortAddress(address), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy address", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(50)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(text)
    }
}

@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(50)) {
        Text(text)
    }
}

/** A rounded card on the surface container color. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, content = content)
}
