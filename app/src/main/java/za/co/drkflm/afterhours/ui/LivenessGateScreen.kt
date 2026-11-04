package za.co.aftrhrs.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.amplifyframework.auth.AWSCredentials
import com.amplifyframework.auth.AWSCredentialsProvider
import com.amplifyframework.auth.AWSTemporaryCredentials
import com.amplifyframework.auth.AuthException
import com.amplifyframework.core.Consumer
import com.amplifyframework.ui.liveness.ui.FaceLivenessDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import za.co.aftrhrs.AppViewModel
import za.co.aftrhrs.data.ApiException
import za.co.aftrhrs.data.ApiClient
import za.co.aftrhrs.data.objectOrNull
import za.co.aftrhrs.data.str
import java.time.Instant

private data class LivenessChallenge(val sessionId: String, val providerSessionId: String, val region: String)

@Composable
fun LivenessGateScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var challenge by remember { mutableStateOf<LivenessChallenge?>(null) }
    var pendingResultSessionId by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var presentingDetector by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var cameraDenied by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf(vm.user.value?.objectOrNull("profile")?.str("displayName").orEmpty()) }

    suspend fun refreshStatus() {
        val data = ApiClient.request("liveness/status").jsonObject
        val liveness = data["liveness"]?.jsonObject
        completed = liveness?.str("status") == "PASSED"
        val session = liveness?.get("session")?.jsonObject
        if (session?.str("status") in setOf("ISSUED", "IN_PROGRESS", "PROCESSING")) {
            pendingResultSessionId = session?.get("sessionId")?.jsonPrimitive?.content
            message = "A previous check is still active. You can check its result, or wait for it to expire before starting another."
        }
        vm.refresh()
    }

    suspend fun startLivenessSession() {
        val data = ApiClient.request(
            "liveness/session", io.ktor.http.HttpMethod.Post, buildJsonObject { }
        ).jsonObject
        val sessionId = data.str("sessionId")
        val providerSessionId = data.str("providerSessionId")
        val region = data.str("region")
        if (sessionId.isBlank() || providerSessionId.isBlank() || region.isBlank()) {
            throw ApiException("The liveness service returned an incomplete camera session. Please contact support.")
        }
        challenge = LivenessChallenge(sessionId, providerSessionId, region)
        presentingDetector = true
    }

    suspend fun processResult(sessionId: String) {
        running = true
        message = "Checking your result securely…"
        try {
            var finalStatus: String? = null
            for (attempt in 0 until 8) {
                val result = ApiClient.request("liveness/result", io.ktor.http.HttpMethod.Post,
                    buildJsonObject { put("sessionId", sessionId) }).jsonObject
                finalStatus = result.str("status")
                if (finalStatus !in setOf("IN_PROGRESS", "CREATED")) break
                delay(if (attempt == 0) 2_000 else 3_000)
            }
            if (finalStatus in setOf("IN_PROGRESS", "CREATED")) {
                message = "Your check is still processing. Check again in a moment."
                return
            }
            refreshStatus()
            pendingResultSessionId = null
            challenge = null
            message = if (completed) null else "AFTRHRS did not approve this attempt. Try again in good light."
        } catch (_: Exception) {
            message = "We couldn’t retrieve the result yet. Reconnect and check this attempt again; your account stays locked until AFTRHRS confirms it."
        } finally {
            running = false
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraDenied = false
            scope.launch {
                running = true
                try {
                    startLivenessSession()
                } catch (error: ApiException) {
                    message = error.message ?: "The liveness service rejected the request. Please try again shortly."
                } catch (_: Exception) {
                    message = "We couldn’t start the camera check. Check your connection and try again shortly."
                } finally { running = false }
            }
        } else cameraDenied = true
    }

    LaunchedEffect(Unit) {
        try { refreshStatus() }
        catch (_: Exception) { message = "We couldn’t check your liveness status. Connect to the internet and try again." }
    }

    if (presentingDetector && challenge != null) {
        val current = challenge!!
        FaceLivenessDetector(
            sessionId = current.providerSessionId,
            region = current.region,
            credentialsProvider = BackendLivenessCredentialsProvider(current.sessionId),
            onComplete = {
                presentingDetector = false
                pendingResultSessionId = current.sessionId
                scope.launch { processResult(current.sessionId) }
            },
            onError = {
                presentingDetector = false
                challenge = null
                scope.launch { runCatching { ApiClient.request("liveness/abort", io.ktor.http.HttpMethod.Post, buildJsonObject { put("sessionId", current.sessionId) }) } }
                message = "The camera check stopped before it finished. You can safely try again."
            }
        )
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(if (completed) "Liveness confirmed" else "A quick camera safety check", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))
        Text(
            if (completed) "Your liveness check was confirmed by AFTRHRS. Continue to your profile and the app."
            else "AFTRHRS checks that a real person is present before profile setup and member features become available. This checks liveness only; it does not verify identity or age. ID verification is optional and can be completed later for the verified badge.",
            color = Muted
        )
        Spacer(Modifier.height(18.dp))
        if (!completed) {
            Column(Modifier.fillMaxWidth().glassCard(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("• Use the front camera in a well-lit place")
                Text("• Follow the on-screen movement and light prompts")
                Text("• No gallery or file uploads are used")
            }
            if (cameraDenied) Text("Camera access is needed for this check. Allow AFTRHRS to use the camera, then try again.", color = MaterialTheme.colorScheme.error)
            message?.let { Text(it, color = Muted, modifier = Modifier.padding(top = 12.dp)) }
            pendingResultSessionId?.let { id ->
                TextButton(enabled = !running, onClick = { scope.launch { processResult(id) } }) { Text("Check previous attempt") }
            }
            Spacer(Modifier.height(12.dp))
            NeonButton(if (running) "Working…" else if (message == null) "Start liveness check" else "Try again", !running) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    scope.launch {
                        running = true
                        try {
                            startLivenessSession()
                        } catch (error: ApiException) {
                            message = error.message ?: "The liveness service rejected the request. Please try again shortly."
                        } catch (_: Exception) { message = "We couldn’t start the camera check. Check your connection and try again shortly." }
                        finally { running = false }
                    }
                } else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        } else if (vm.user.value?.let { user ->
                user["profileComplete"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: !user.objectOrNull("profile")?.str("displayName").isNullOrBlank()
            } != true) {
            Spacer(Modifier.height(20.dp))
            Text("Create your profile", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            NeonButton("Continue to AFTRHRS", displayName.trim().length >= 2 && !running) {
                running = true
                scope.launch {
                    try {
                        ApiClient.request("profiles/me", io.ktor.http.HttpMethod.Patch, buildJsonObject { put("displayName", displayName.trim()) })
                        vm.refresh()
                        message = null
                    } catch (_: Exception) { message = "We couldn’t save your profile. Please try again." }
                    finally { running = false }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { vm.logout() }) { Text("Sign out") }
    }
}

private class BackendLivenessCredentialsProvider(private val sessionId: String) : AWSCredentialsProvider<AWSCredentials> {
    override fun fetchAWSCredentials(
        onSuccess: Consumer<AWSCredentials>,
        onError: Consumer<AuthException>
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val value = ApiClient.request(
                    "liveness/credentials", io.ktor.http.HttpMethod.Post,
                buildJsonObject { put("sessionId", sessionId) }
                ).jsonObject
                val expiresAt = Instant.parse(value.str("expiration"))
                val smithyExpiration = aws.smithy.kotlin.runtime.time.Instant.fromEpochSeconds(
                    expiresAt.epochSecond, expiresAt.nano
                )
                onSuccess.accept(AWSTemporaryCredentials(
                    value.str("accessKeyId"), value.str("secretAccessKey"), value.str("sessionToken"), smithyExpiration
                ))
            } catch (error: Exception) {
                onError.accept(AuthException("Unable to obtain liveness credentials", "Please retry the liveness check.", error))
            }
        }
    }
}
