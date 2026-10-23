package dev.azwall.app.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.azwall.app.R
import dev.azwall.app.ui.components.PrimaryButton
import dev.azwall.app.ui.components.SecondaryButton
import kotlinx.coroutines.launch

private enum class Step { WELCOME, BACKUP, VERIFY, IMPORT }

/** What onboarding needs from the app: a new phrase, a check of what the user typed, and creating the wallet. */
class OnboardingActions(
    val newPhrase: suspend () -> String,
    /** Null when the text is a usable recovery phrase or signing key, else what is wrong with it. */
    val check: suspend (String) -> String?,
    /** Creates the wallet from a recovery phrase or a signing key. */
    val onCreate: (String) -> Unit,
)

/** First run: create a wallet (back its 24 words up, then check three of them) or restore one. */
@Composable
fun OnboardingFlow(busy: Boolean, actions: OnboardingActions) {
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    // Not saveable: the saved state may be written to disk, and a new phrase is as good if the process restarts.
    var phrase by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val words = phrase?.split(' ')
    when {
        step == Step.IMPORT -> Import(busy, actions.check, onBack = { step = Step.WELCOME }, onImport = actions.onCreate)
        step == Step.BACKUP && words != null -> Backup(words, onBack = { step = Step.WELCOME }, onNext = { step = Step.VERIFY })
        step == Step.VERIFY && words != null -> Verify(words, busy, onBack = { step = Step.BACKUP }, onDone = { actions.onCreate(phrase!!) })
        else -> Welcome(
            onCreate = { scope.launch { runCatching { actions.newPhrase() }.onSuccess { phrase = it; step = Step.BACKUP } } },
            onImport = { step = Step.IMPORT },
        )
    }
}

@Composable
internal fun Screen(onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Box(Modifier.height(56.dp)) {
            onBack?.let { IconButton(onClick = it, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }
        }
        content()
    }
}

@Composable
private fun Welcome(onCreate: () -> Unit, onImport: () -> Unit) = Screen {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Image(painterResource(R.drawable.azwall_logo), contentDescription = null, modifier = Modifier.height(72.dp).align(Alignment.CenterHorizontally))
        Text("Azwall", style = MaterialTheme.typography.displaySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text("A private wallet for Aztec.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Feature(Icons.Outlined.Lock, "Private by default", "Balances and transfers stay encrypted. Only you can see them.")
        Feature(Icons.Outlined.Bolt, "Proven on your phone", "Every transaction is proven on this device, natively, in seconds.")
        Feature(Icons.Outlined.Hub, "Every Aztec network", "Switch between networks running different Aztec versions.")
    }
    PrimaryButton("Create a new wallet", onClick = onCreate)
    Spacer(Modifier.height(12.dp))
    SecondaryButton("Restore a wallet", onClick = onImport)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shown when a vault exists: one tap, then biometric or device credential. */
@Composable
fun LockScreen(busy: Boolean, onUnlock: () -> Unit) = Screen {
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.azwall_logo), contentDescription = null, modifier = Modifier.height(120.dp))
        Spacer(Modifier.height(16.dp))
        Text("Azwall", style = MaterialTheme.typography.displaySmall)
        Text("Your wallet is locked", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    PrimaryButton("Unlock", busy = busy, onClick = onUnlock)
    Spacer(Modifier.height(24.dp))
}
