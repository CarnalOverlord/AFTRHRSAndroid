package za.co.aftrhrs.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.text.style.TextAlign
import za.co.aftrhrs.AppViewModel
import za.co.aftrhrs.data.ApiClient
import za.co.aftrhrs.data.str
import za.co.aftrhrs.data.strOrNull
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

private data class IdPhotoStep(val id: String, val slug: String, val title: String, val help: String)
private val idPhotoSteps = listOf(
    IdPhotoStep("ID_FRONT", "id-front", "Front of your ID", "Keep every edge and detail visible."),
    IdPhotoStep("ID_BACK", "id-back", "Back of your ID", "Photograph the back of the same document."),
    IdPhotoStep("SELFIE_WITH_ID", "selfie-with-id", "Selfie holding your ID", "Hold the front beside your face so both are clear.")
)

@Composable
fun OptionalIdentityVerificationScreen(vm: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("NOT_STARTED") }
    var note by remember { mutableStateOf<String?>(null) }
    var captured by remember { mutableStateOf(setOf<String>()) }
    var active by remember { mutableStateOf<IdPhotoStep?>(null) }
    var photo by remember { mutableStateOf<File?>(null) }
    var working by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    fun openCamera(step: IdPhotoStep, launch: (android.net.Uri) -> Unit) {
        active = step
        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
        photo = File(directory, "badge-${step.slug}.jpg")
        launch(FileProvider.getUriForFile(context, "${context.packageName}.files", photo!!))
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val step = active
        val file = photo
        if (success && step != null && file != null) vm.runTask {
            working = true
            try {
                ApiClient.upload("verification/evidence/${step.slug}", file)
                captured = captured + step.id
            } finally { working = false }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val step = active
        if (granted && step != null) openCamera(step, takePicture::launch)
        else loadError = "Camera access is required to submit ID verification photos."
    }

    androidx.compose.runtime.LaunchedEffect(status) {
        do {
            try {
                val data = ApiClient.request("verification/status").jsonObject
                status = data["status"]?.jsonPrimitive?.contentOrNull ?: "NOT_STARTED"
                note = data["note"]?.jsonPrimitive?.contentOrNull
                captured = data["evidence"]?.jsonArray?.mapNotNull { item ->
                    item.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                }?.toSet() ?: emptySet()
                loadError = null
            } catch (_: Exception) { loadError = "Unable to check ID verification status." }
            if (status == "PENDING" || status == "REVIEW") delay(15_000)
        } while (status == "PENDING" || status == "REVIEW")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Optional ID verification", style = MaterialTheme.typography.headlineSmall)
        Text("This step is optional and separate from the liveness check. Submit your ID photos for manual review to request the verified badge.", color = Muted)
        when (status) {
            "APPROVED" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Icon(Icons.Default.Badge, contentDescription = null, tint = Lavender)
                Text("Your verified badge is active.")
            }
            "PENDING", "REVIEW" -> Text("Your ID photos are waiting for review.${note?.let { " $it" } ?: ""}", color = Muted)
            "REJECTED" -> Text(note?.takeIf { it.isNotBlank() } ?: "The previous submission needs clearer photos. You can retake and resubmit.", color = MaterialTheme.colorScheme.error)
            else -> idPhotoSteps.forEachIndexed { index, step ->
                Row(Modifier.fillMaxWidth().glassCard(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${index + 1}. ${step.title}")
                        Text(step.help, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    ElectricButton(onClick = {
                        active = step
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera(step, takePicture::launch)
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    }) { Text(if (step.id in captured) "Retake" else "Camera") }
                }
            }
        }
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (status !in setOf("APPROVED", "PENDING", "REVIEW")) {
            NeonButton(if (working) "Submitting…" else "Submit for badge review", !working && captured.containsAll(idPhotoSteps.map { it.id })) {
                working = true
                vm.runTask {
                    try {
                        ApiClient.request("verification/submit", io.ktor.http.HttpMethod.Post)
                        status = "PENDING"
                        note = null
                    } finally { working = false }
                }
            }
        }
        Text("Identity photos are used only for manual review and are not shown on your profile.", color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        androidx.compose.material3.TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close") }
    }
}
