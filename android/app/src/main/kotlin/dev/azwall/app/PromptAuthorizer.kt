package dev.azwall.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.azwall.core.vault.AuthenticationCancelled
import dev.azwall.core.vault.CipherAuthorizer
import java.util.concurrent.CompletableFuture
import javax.crypto.Cipher

/**
 * Shows BiometricPrompt over the activity in front, with the vault cipher as its CryptoObject: the authentication
 * unlocks that one operation of the key, nothing else. Called from the vault's IO thread, which it blocks until the
 * user answers.
 */
class PromptAuthorizer(app: Application) : CipherAuthorizer {
    @Volatile
    private var front: FragmentActivity? = null

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                front = activity as? FragmentActivity
            }

            override fun onActivityPaused(activity: Activity) {
                if (front === activity) front = null
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun authorize(cipher: Cipher, title: String): Cipher {
        // The prompt runs on the main thread: waiting for it there would never end.
        check(Looper.myLooper() != Looper.getMainLooper()) { "the vault was opened on the main thread" }
        val activity = front ?: throw AuthenticationCancelled()
        val answer = CompletableFuture<Cipher?>()
        activity.runOnUiThread {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        answer.complete(result.cryptoObject?.cipher)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        answer.complete(null)
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }
        return answer.get() ?: throw AuthenticationCancelled()
    }
}
