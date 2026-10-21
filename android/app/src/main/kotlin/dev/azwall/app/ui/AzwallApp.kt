package dev.azwall.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.azwall.app.AppPhase
import dev.azwall.app.AppViewModel
import dev.azwall.app.Auth
import dev.azwall.app.ui.onboarding.LockScreen
import dev.azwall.app.ui.onboarding.OnboardingActions
import dev.azwall.app.ui.onboarding.OnboardingFlow

/** Root of the UI: onboarding, lock screen, or the unlocked wallet. [auth] builds a biometric prompt. */
@Composable
fun AzwallApp(vm: AppViewModel, auth: (title: String) -> Auth) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(friendlyError(it))
        }
    }
    LaunchedEffect(Unit) { vm.boot() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            when (val p = phase) {
                AppPhase.Booting -> Centered { CircularProgressIndicator() }
                AppPhase.Onboarding -> OnboardingFlow(
                    busy,
                    OnboardingActions(vm::newRecoveryPhrase, vm::checkSecret) { secret -> vm.createWallet(secret, "Account 1", auth("Secure your wallet")) },
                )
                AppPhase.Locked -> LockScreen(busy) { vm.unlock(auth("Unlock Azwall")) }
                is AppPhase.Unlocked -> WalletScreens(vm, p.session, busy, auth) { snackbar.currentSnackbarData?.dismiss() }
                is AppPhase.Failed -> Centered { Text("Azwall could not start:\n${p.message}", textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        content()
    }
}
