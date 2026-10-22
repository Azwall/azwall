package dev.azwall.app.ui.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.PrimaryButton
import dev.azwall.app.ui.components.PrivacySwitch
import dev.azwall.app.ui.components.TokenBadge
import dev.azwall.app.ui.send.Header
import dev.azwall.app.ui.send.QrScanner
import dev.azwall.app.ui.send.RecipientField
import dev.azwall.app.ui.sheets.ReviewSheet
import dev.azwall.core.wallet.Amounts
import dev.azwall.core.wallet.PaymentRequest
import dev.azwall.core.wallet.Review
import dev.azwall.core.wallet.TokenView

/** Mints one of the account's tokens, laid out like Send: to this account (recipient left empty) or to anyone. */
@Composable
fun MintScreen(token: TokenView, network: String, fee: String, busy: Boolean, onBack: () -> Unit, onMint: (amount: String, private: Boolean, to: String) -> Unit) {
    BackHandler(onBack = onBack)
    var private by rememberSaveable { mutableStateOf(true) }
    var to by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("1000") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanNote by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val toError = if (to.isNotEmpty() && !PaymentRequest.isAddress(to)) "an Aztec address is 0x followed by 64 hexadecimal characters" else null
    val amountError = amount.takeIf { it.isNotBlank() }?.let { runCatching { Amounts.parse(it, token.info.decimals) }.exceptionOrNull()?.message }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        Header("Mint ${token.info.symbol}", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PrivacySwitch(private, { private = it })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TokenBadge(token.info.symbol, token.info.address, size = 32.dp)
                Text("${token.info.name} (${token.info.symbol})", style = MaterialTheme.typography.titleSmall)
            }
            RecipientField("Recipient (empty: this account)", to, toError ?: scanNote, onScan = { scanning = true }) { to = it.trim(); scanNote = null }
            OutlinedTextField(
                amount, { amount = it }, label = { Text("Amount") }, suffix = { Text(token.info.symbol) }, isError = amountError != null,
                supportingText = { amountError?.let { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (private) "Private: the new tokens are a note only the recipient can see." else "Public: the new balance is visible to everyone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val valid = amount.isNotBlank() && amountError == null && toError == null
        PrimaryButton(if (private) "Mint privately" else "Mint publicly", enabled = valid, busy = busy) { reviewing = true }
        Spacer(Modifier.height(16.dp))
    }
    if (reviewing) {
        val shown = "${Amounts.format(Amounts.parse(amount, token.info.decimals), token.info.decimals, token.info.decimals)} ${token.info.symbol}"
        ReviewSheet(Review.mint(shown, private, to, network, fee), onDismiss = { reviewing = false }) { reviewing = false; onMint(amount, private, to) }
    }
    if (scanning) QrScanner(onDismiss = { scanning = false }, onResult = { text ->
        scanning = false
        val request = PaymentRequest.parse(text)
        if (request == null) scanNote = "This QR code is not an Aztec address" else to = request.address
    })
}
