package dev.azwall.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.azwall.app.ui.components.Panel
import dev.azwall.app.ui.components.PrimaryButton
import dev.azwall.app.ui.components.SecureWindow
import dev.azwall.app.ui.theme.MonoStyle
import kotlinx.coroutines.launch

/** The words, numbered, in two columns: the order matters as much as the words. */
@Composable
fun PhraseGrid(words: List<String>) = Panel {
    val half = (words.size + 1) / 2
    Row(Modifier.padding(16.dp)) {
        listOf(0 until half, half until words.size).forEach { range ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                range.forEach { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}".padStart(2), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(words[i], style = MonoStyle)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Backup(words: List<String>, onBack: () -> Unit, onNext: () -> Unit) = Screen(onBack) {
    var saved by remember { mutableStateOf(false) }
    SecureWindow()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your recovery phrase", style = MaterialTheme.typography.headlineSmall)
        Text(
            "These ${words.size} words are your wallet: every account it creates comes back from them. Write them down " +
                "in order and keep them offline. Anyone who has them controls your funds; without them a lost phone means lost funds.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PhraseGrid(words)
        // The whole line ticks the box, not only the box itself.
        Row(Modifier.toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = saved, onCheckedChange = null)
            Text("I wrote the ${words.size} words down, in order")
        }
    }
    PrimaryButton("Next", enabled = saved, onClick = onNext)
    Spacer(Modifier.height(24.dp))
}

/** Three words asked back, at random places: a phrase copied wrong is found now, not on the day it is needed. */
@Composable
internal fun Verify(words: List<String>, busy: Boolean, onBack: () -> Unit, onDone: () -> Unit) = Screen(onBack) {
    val asked = remember(words) { words.indices.shuffled().take(3).sorted() }
    val answers = remember(words) { mutableStateListOf("", "", "") }
    SecureWindow()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Check your copy", style = MaterialTheme.typography.headlineSmall)
        Text("Type these words from what you wrote down.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        asked.forEachIndexed { n, i ->
            val wrong = answers[n].isNotBlank() && answers[n].trim().lowercase() != words[i]
            OutlinedTextField(
                answers[n],
                { answers[n] = it },
                label = { Text("Word ${i + 1}") },
                singleLine = true,
                isError = wrong,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    val right = asked.indices.all { answers[it].trim().lowercase() == words[asked[it]] }
    PrimaryButton("Create wallet", enabled = right, busy = busy, onClick = onDone)
    Spacer(Modifier.height(24.dp))
}

/**
 * Restores from a recovery phrase (account 1; add accounts again to bring the next ones back) or a signing key.
 * Checked when the user taps Restore, not while typing: a phrase is only valid once all its words are there.
 */
@Composable
internal fun Import(busy: Boolean, check: suspend (String) -> String?, onBack: () -> Unit, onImport: (String) -> Unit) = Screen(onBack) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SecureWindow()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Restore a wallet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Type your recovery phrase, words separated by spaces. Accounts you added come back when you add them again, " +
                "in the same order. A 64-character signing key works too.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = null },
            label = { Text("Recovery phrase or signing key") },
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            textStyle = MonoStyle,
            minLines = 4,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    PrimaryButton("Restore", enabled = input.isNotBlank() && error == null, busy = busy || checking) {
        scope.launch {
            checking = true
            error = check(input)
            checking = false
            if (error == null) onImport(input)
        }
    }
    Spacer(Modifier.height(24.dp))
}
