package dev.azwall.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.azwall.app.ui.AzwallApp
import dev.azwall.app.ui.theme.AzwallTheme

/** A FragmentActivity, because the biometric prompt is shown by a fragment. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AzwallApplication).container
        setContent {
            AzwallTheme {
                val vm = viewModel { AppViewModel(container) }
                AzwallApp(vm) { title -> authenticator(title) }
            }
        }
    }
}
