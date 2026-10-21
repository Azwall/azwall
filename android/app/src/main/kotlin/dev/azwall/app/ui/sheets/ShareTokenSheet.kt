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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import dev.azwall.core.wallet.PaymentRequest
import dev.azwall.core.wallet.TokenView

/**
 * A token's QR code and link, for another wallet to follow it: a wallet only sees the tokens it follows, so whoever
 * receives one it does not know adds it first (Add a token, scan or paste).
 */
@Composable
fun ShareTokenSheet(token: TokenView, network: String, networkLabel: String, onDismiss: () -> Unit) = Sheet("Share ${token.info.symbol}", onDismiss, fullHeight = true) {
    TokenShareCard(token, network, networkLabel)
}

/** The token, its QR code and address, and the share button: in the share sheet and on the token's manage screen. */
@Composable
fun TokenShareCard(token: TokenView, network: String, networkLabel: String) {
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
