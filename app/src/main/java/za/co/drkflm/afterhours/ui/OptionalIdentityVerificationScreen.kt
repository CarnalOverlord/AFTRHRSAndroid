package za.co.aftrhrs.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import za.co.aftrhrs.AppViewModel
import za.co.aftrhrs.data.ApiClient
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
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

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
            error = null
            try {
                ApiClient.upload("verification/evidence/${step.slug}", file)
                captured = captured + step.id
            } catch (_: Exception) {
                error = "We couldn’t upload that photo. Check your connection and try again."
            } finally { working = false }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val step = active
        if (granted && step != null) openCamera(step, takePicture::launch)
        else error = "Camera access is needed to capture your ID photos. Allow camera access, then try again."
    }

    LaunchedEffect(status) {
        loading = true
        do {
            try {
                val data = ApiClient.request("verification/status").jsonObject
                status = data["status"]?.jsonPrimitive?.contentOrNull ?: "NOT_STARTED"
                note = data["note"]?.jsonPrimitive?.contentOrNull
                captured = data["evidence"]?.jsonArray?.mapNotNull { item ->
                    item.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                }?.toSet() ?: emptySet()
                error = null
            } catch (_: Exception) {
                error = "We couldn’t load your verification status. Check your connection and retry."
            } finally { loading = false }
            if (status == "PENDING" || status == "REVIEW") delay(15_000)
        } while (status == "PENDING" || status == "REVIEW")
    }

    val completedCount = idPhotoSteps.count { it.id in captured }
    val canSubmit = completedCount == idPhotoSteps.size && !working
    val showCaptureSteps = status !in setOf("APPROVED", "PENDING", "REVIEW")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("VERIFIED BADGE", color = Lavender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("ID verification", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.Badge, contentDescription = null, tint = Lavender, modifier = Modifier.size(28.dp))
        }

        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF38104C), Color(0xFF1C1029), Color(0xFF151020))))
                .border(1.dp, Lavender.copy(alpha = .28f), RoundedCornerShape(28.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Badge, contentDescription = null, tint = HotPink, modifier = Modifier.size(22.dp))
                Text("Optional profile badge", color = Lavender, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Text("Show members you’re verified", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(
                "ID verification is a separate, optional review. Your account and liveness check do not depend on completing it.",
                color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Lavender, modifier = Modifier.size(18.dp))
                Text("Your ID evidence is private and reviewed by our team.", color = Color.White.copy(alpha = .82f), style = MaterialTheme.typography.bodySmall)
            }
        }

        when (status) {
            "APPROVED" -> StatusPanel(
                icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF73E6B1), modifier = Modifier.size(30.dp)) },
                title = "You’re verified",
                body = "Your verified badge is active on your profile."
            )
            "PENDING", "REVIEW" -> StatusPanel(
                icon = { if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = Lavender, strokeWidth = 3.dp) else Icon(Icons.Default.Badge, null, tint = Lavender, modifier = Modifier.size(30.dp)) },
                title = "Your submission is in review",
                body = "We’ll update your badge when the review is complete.${note?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
            )
            "REJECTED" -> StatusPanel(
                icon = { Icon(Icons.Default.CameraAlt, contentDescription = null, tint = HotPink, modifier = Modifier.size(28.dp)) },
                title = "A clearer set is needed",
                body = note?.takeIf { it.isNotBlank() } ?: "Retake the photos below and submit them for another review."
            )
        }

        if (showCaptureSteps) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Your photos", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Three clear camera photos are required", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("$completedCount of 3", color = Lavender, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxWidth().glassCard(padding = 0), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                idPhotoSteps.forEachIndexed { index, step ->
                    PhotoStepRow(
                        number = index + 1,
                        step = step,
                        complete = step.id in captured,
                        enabled = !working,
                        onCapture = {
                            error = null
                            active = step
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                openCamera(step, takePicture::launch)
                            } else cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    )
                    if (index < idPhotoSteps.lastIndex) Spacer(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))
                }
            }
            Text(
                "Use the original document. Avoid glare, blur, cropped edges and screenshots. For the selfie, hold the same ID beside your face.",
                color = Muted, style = MaterialTheme.typography.bodySmall
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            NeonButton(if (working) "Uploading…" else "Submit for review", canSubmit) {
                working = true
                error = null
                vm.runTask {
                    try {
                        ApiClient.request("verification/submit", io.ktor.http.HttpMethod.Post)
                        status = "PENDING"
                        note = null
                    } catch (_: Exception) {
                        error = "We couldn’t submit your photos. Check your connection and try again."
                    } finally { working = false }
                }
            }
            if (completedCount < idPhotoSteps.size) {
                Text("Capture all three photos to enable submission.", color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (showCaptureSteps) {
            Column(Modifier.fillMaxWidth().glassCard(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What happens next", color = Color.White, fontWeight = FontWeight.Bold)
                Text("1. Capture the front, back and a selfie holding your ID.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("2. Submit your photos for a manual review.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("3. We’ll add the verified badge after approval.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Done") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PhotoStepRow(number: Int, step: IdPhotoStep, complete: Boolean, enabled: Boolean, onCapture: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape)
                .background(if (complete) Color(0xFF73E6B1).copy(alpha = .16f) else Lavender.copy(alpha = .13f)),
            contentAlignment = Alignment.Center
        ) {
            if (complete) Icon(Icons.Default.CheckCircle, contentDescription = "Photo captured", tint = Color(0xFF73E6B1), modifier = Modifier.size(23.dp))
            else Text(number.toString(), color = Lavender, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(step.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(step.help, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        ElectricButton(onClick = onCapture, modifier = Modifier.width(116.dp), enabled = enabled) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(7.dp))
            Text(if (complete) "Retake" else "Capture", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusPanel(icon: @Composable () -> Unit, title: String, body: String) {
    Row(
        Modifier.fillMaxWidth().glassCard(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        icon()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
