package za.co.drkflm.afterhours

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import za.co.drkflm.afterhours.ui.*

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(false)
    private var lockEnabled by mutableStateOf(false)
    private var authenticating by mutableStateOf(false)
    private var authError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockEnabled = getPreferences(MODE_PRIVATE).getBoolean("app_lock", false)
        locked = lockEnabled
        setContent {
            AftrhrsTheme {
                val vm: AppViewModel = viewModel()
                AftrhrsRoot(vm, locked, authenticating, authError, lockEnabled,
                    unlock = ::authenticateUnlock,
                    setLock = { enabled -> authenticate("${if (enabled) "Turn on" else "Turn off"} App Lock") { lockEnabled = enabled; locked = false; getPreferences(MODE_PRIVATE).edit().putBoolean("app_lock", enabled).apply() } })
            }
        }
    }
    override fun onStop() { super.onStop(); if (lockEnabled && !isChangingConfigurations) locked = true }
    override fun onStart() { super.onStart(); if (locked) authenticateUnlock() }
    private fun authenticateUnlock() = authenticate("Unlock AFTRHRS") { locked = false }
    private fun authenticate(reason: String, success: () -> Unit) {
        if (authenticating) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) { authError = "Set up a screen lock or biometrics in Android Settings first."; return }
        authenticating = true; authError = null
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { authenticating = false; success() }
            override fun onAuthenticationError(code: Int, message: CharSequence) { authenticating = false; if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) authError = message.toString() }
            override fun onAuthenticationFailed() { authError = "Authentication was not successful." }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("AFTRHRS").setSubtitle(reason).setAllowedAuthenticators(authenticators).build())
    }
}
