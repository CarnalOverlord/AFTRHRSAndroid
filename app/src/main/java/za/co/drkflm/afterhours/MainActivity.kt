package za.co.drkflm.afterhours

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    private var pushIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockEnabled = getPreferences(MODE_PRIVATE).getBoolean("app_lock", false)
        locked = lockEnabled
        pushIntent = intent
        setContent {
            AftrhrsTheme {
                val vm: AppViewModel = viewModel()
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(vm.phase.value) {
                    if (vm.phase.value == SessionPhase.SignedIn && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(pushIntent,vm.phase.value){
                    if(vm.phase.value==SessionPhase.SignedIn){pushIntent?.let{vm.routePush(it.getStringExtra("conversationId"),it.getStringExtra("groupId"));pushIntent=null}}
                }
                AftrhrsRoot(vm, locked, authenticating, authError, lockEnabled,
                    unlock = ::authenticateUnlock,
                    setLock = { enabled -> authenticate("${if (enabled) "Turn on" else "Turn off"} App Lock") { lockEnabled = enabled; locked = false; getPreferences(MODE_PRIVATE).edit().putBoolean("app_lock", enabled).apply() } })
            }
        }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);pushIntent=intent}
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
