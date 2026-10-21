package dev.azwall.app.ui.send

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.NO_TOKENS
import dev.azwall.app.ui.components.PrimaryButton
import dev.azwall.app.ui.components.TokenBadge
import dev.azwall.app.ui.theme.MonoStyle
import dev.azwall.core.pxe.TransferKind
import dev.azwall.core.wallet.Amounts
import dev.azwall.core.wallet.PaymentRequest
import dev.azwall.core.wallet.TokenView
import java.math.BigInteger

/** Send moves funds to someone else; Shield moves the user's own funds between public and private. */
enum class TransferMode { SEND, SHIELD }


@Composable
fun TransferScreen(
    mode: TransferMode,
    tokens: List<TokenView>,
    me: String,
    network: String,
    busy: Boolean,
    onBack: () -> Unit,
    onSubmit: (TransferKind, TokenView, String, BigInteger) -> Unit,
) {
    var tokenIndex by rememberSaveable { mutableIntStateOf(0) }
    // Shield starts on the side that holds funds: make public funds private, else private funds public.
    var publicSide by rememberSaveable {
        mutableStateOf(mode == TransferMode.SHIELD && (tokens.firstOrNull()?.balance?.publicAmount?.signum() ?: 0) > 0)
    }
    var to by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanNote by rememberSaveable { mutableStateOf<String?>(null) }
    val token = tokens.getOrNull(tokenIndex) ?: return NoTokens(mode, onBack)
    val kind = kindOf(mode, publicSide)
    val available = available(token, kind)
    val amount = runCatching { Amounts.parse(amountText, token.info.decimals) }
    val amountError = amountError(amountText, amount, available)
    val toError = if (mode == TransferMode.SEND) recipientError(to, me) else null
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        Header(if (mode == TransferMode.SEND) "Send" else "Shield", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ModeSelector(mode, publicSide) { publicSide = it }
            TokenPicker(tokens, tokenIndex) { tokenIndex = it }
            if (mode == TransferMode.SEND) RecipientField(to, toError ?: scanNote, onScan = { scanning = true }) { to = it.trim(); scanNote = null }
            AmountField(amountText, token, available, amountError) { amountText = it }
            Text(explanation(kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val valid = amount.isSuccess && amountError == null && toError == null && (mode == TransferMode.SHIELD || to.isNotEmpty())
        PrimaryButton(actionLabel(kind), enabled = valid, busy = busy) { onSubmit(kind, token, to, amount.getOrThrow()) }
        Spacer(Modifier.height(16.dp))
    }
    if (scanning) QrScanner(onDismiss = { scanning = false }, onResult = { text ->
        scanning = false
        val scan = readScan(text, network, tokens)
        scan.to?.let { to = it }
        scan.tokenIndex?.let { tokenIndex = it }
        scan.amount?.let { amountText = it }
        scanNote = scan.note
    })
}

/** What a scanned QR code fills in, and what to tell the user about it. */
private data class Scan(val to: String? = null, val tokenIndex: Int? = null, val amount: String? = null, val note: String? = null)

private fun readScan(text: String, network: String, tokens: List<TokenView>): Scan {
    val request = PaymentRequest.parse(text) ?: return Scan(note = "This QR code is not an Aztec address")
    val index = request.token?.let { t -> tokens.indexOfFirst { it.info.address.equals(t, ignoreCase = true) }.takeIf { it >= 0 } }
    val note = when {
        request.network != null && request.network != network -> "This request is for ${request.network}, not $network: check before sending"
        request.token != null && index == null -> "The requested token is not in your list: add it first"
        else -> null
    }
    return Scan(to = request.address, tokenIndex = index, amount = request.amount, note = note)
}

private fun kindOf(mode: TransferMode, publicSide: Boolean): TransferKind = when (mode) {
    TransferMode.SEND -> if (publicSide) TransferKind.PUBLIC else TransferKind.PRIVATE
    TransferMode.SHIELD -> if (publicSide) TransferKind.SHIELD else TransferKind.UNSHIELD
}

/** Private sends and unshielding spend private notes; the others spend the public balance. */
private fun available(token: TokenView, kind: TransferKind): BigInteger {
    val b = token.balance ?: return BigInteger.ZERO
    return if (kind == TransferKind.PRIVATE || kind == TransferKind.UNSHIELD) b.privateAmount else b.publicAmount
}

private fun amountError(text: String, amount: Result<BigInteger>, available: BigInteger): String? = when {
    text.isBlank() -> null
    amount.isFailure -> amount.exceptionOrNull()?.message
    amount.getOrThrow() > available -> "more than available"
    else -> null
}

private fun recipientError(to: String, me: String): String? = when {
    to.isEmpty() -> null
    !PaymentRequest.isAddress(to) -> "an Aztec address is 0x followed by 64 hexadecimal characters"
    to.equals(me, ignoreCase = true) -> "this is your own address"
    else -> null
}

private fun explanation(kind: TransferKind): String = when (kind) {
    TransferKind.PRIVATE -> "Private: the amount, the sender and the recipient stay hidden. Proven on this phone."
    TransferKind.PUBLIC -> "Public: visible to everyone on chain, like a transparent transfer."
    TransferKind.SHIELD -> "Moves public funds into your private balance. The amount is visible once, then hidden."
    TransferKind.UNSHIELD -> "Moves private funds to your public balance, where everyone can see them."
}

private fun actionLabel(kind: TransferKind): String = when (kind) {
    TransferKind.PRIVATE -> "Send privately"
    TransferKind.PUBLIC -> "Send publicly"
    TransferKind.SHIELD -> "Make private"
    TransferKind.UNSHIELD -> "Make public"
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ModeSelector(mode: TransferMode, publicSide: Boolean, onChange: (Boolean) -> Unit) {
    val labels = if (mode == TransferMode.SEND) listOf("Private", "Public") else listOf("Make private", "Make public")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            // SEND: index 1 is public. SHIELD: index 0 ("make private") spends the public side.
            val isPublic = if (mode == TransferMode.SEND) i == 1 else i == 0
            SegmentedButton(
                selected = publicSide == isPublic,
                onClick = { onChange(isPublic) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun TokenPicker(tokens: List<TokenView>, selected: Int, onSelect: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val token = tokens[selected].info
    Surface(onClick = { open = true }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TokenBadge(token.symbol, token.address, size = 32.dp)
            Text("${token.name} (${token.symbol})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            // Says the row opens a list of tokens.
            Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, "Choose a token")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tokens.forEachIndexed { i, t ->
                DropdownMenuItem(text = { Text("${t.info.name} (${t.info.symbol})") }, onClick = { onSelect(i); open = false })
            }
        }
    }
}

@Composable
private fun RecipientField(value: String, error: String?, onScan: () -> Unit, onChange: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Recipient address") },
        textStyle = MonoStyle,
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        trailingIcon = {
            Row {
                IconButton(onClick = onScan) { Icon(Icons.Outlined.QrCodeScanner, "Scan a QR code") }
                IconButton(onClick = { clipboard.getText()?.text?.let(onChange) }) { Icon(Icons.Outlined.ContentPaste, "Paste") }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AmountField(value: String, token: TokenView, available: BigInteger, error: String?, onChange: (String) -> Unit) {
    val decimals = token.info.decimals
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Amount") },
        suffix = { Text(token.info.symbol) },
        isError = error != null,
        supportingText = { Text(error ?: "Available: ${Amounts.format(available, decimals)} ${token.info.symbol}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        trailingIcon = {
            TextButton(onClick = { onChange(Amounts.format(available, decimals, decimals).replace(",", "")) }, enabled = available.signum() > 0) {
                Text("Max")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Without any token there is nothing to move: say so, under the usual header. */
@Composable
private fun NoTokens(mode: TransferMode, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Header(if (mode == TransferMode.SEND) "Send" else "Shield", onBack)
        Text(NO_TOKENS, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
    }
}
