package dev.azwall.app.ui.sheets

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.CopyAddress
import dev.azwall.app.ui.components.SecondaryButton
import dev.azwall.app.ui.components.TokenBadge
import dev.azwall.app.ui.receive.qrBitmap
import dev.azwall.app.ui.theme.WalletTheme
import dev.azwall.core.wallet.PaymentRequest
import dev.azwall.core.wallet.TokenView

/**
 * A token's QR code and link, for another wallet to follow it: a wallet only sees the tokens it follows, so whoever
 * receives one it does not know adds it first (Add a token, scan or paste).
 */
@Composable
fun ShareTokenSheet(
    token: TokenView,
    network: String,
    networkLabel: String,
    /** Stops following the token (the home screen's list); null where it cannot be removed. */
    onRemove: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) = Sheet("Share ${token.info.symbol}", onDismiss, fullHeight = true) {
    TokenShareCard(token, network, networkLabel)
    onRemove?.let { RemoveToken(token.info.symbol) { it(); onDismiss() } }
}

/** Removing only hides the token here: its balance stays on chain, and adding it again shows it. Asked first. */
@Composable
private fun RemoveToken(symbol: String, onRemove: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    TextButton(onClick = { asking = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Remove $symbol from my list", color = WalletTheme.colors.danger)
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Remove $symbol?") },
            text = { Text("It leaves your list only: what you hold stays on chain, and adding the token again shows it.") },
            confirmButton = { TextButton(onClick = { asking = false; onRemove() }) { Text("Remove", color = WalletTheme.colors.danger) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

/** The sheet's content: the token, its QR code and address, and the share button. */
@Composable
private fun TokenShareCard(token: TokenView, network: String, networkLabel: String) {
    val context = LocalContext.current
    val link = remember(token.info.address, network) { PaymentRequest(token.info.address, network).toUri() }
    val qr = remember(link) { qrBitmap(link) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TokenBadge(token.info.symbol, token.info.address)
            Text("${token.info.name} (${token.info.symbol})", style = MaterialTheme.typography.titleMedium)
        }
        Image(
            qr,
            contentDescription = "QR code of the token",
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp),
        )
        CopyAddress(token.info.address)
        Text(
            "Scan it in Add a token on another wallet to see its ${token.info.symbol} balances. The token exists on $networkLabel only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        SecondaryButton("Share link") {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
            context.startActivity(Intent.createChooser(send, "Share ${token.info.symbol}"))
        }
    }
}
