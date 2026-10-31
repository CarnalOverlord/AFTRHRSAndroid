package za.co.drkflm.afterhours.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LocalLifecycleOwner
import za.co.drkflm.afterhours.data.Presence
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.*
import za.co.drkflm.afterhours.AppViewModel
import za.co.drkflm.afterhours.R
import za.co.drkflm.afterhours.SessionPhase
import za.co.drkflm.afterhours.data.ApiClient
import za.co.drkflm.afterhours.data.obj
import java.io.File
import java.time.LocalDate

@Composable fun AftrhrsRoot(vm: AppViewModel, locked: Boolean, authenticating: Boolean, authError: String?, lockEnabled: Boolean, unlock: () -> Unit, setLock: (Boolean) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val presenceAllowed = vm.phase.value == SessionPhase.SignedIn && vm.canEnter && !locked
    DisposableEffect(lifecycleOwner, presenceAllowed) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && presenceAllowed) { Presence.connect(); vm.runTask { vm.refresh() } }
            if (event == Lifecycle.Event.ON_STOP) Presence.disconnect()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (presenceAllowed && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) Presence.connect()
        else Presence.disconnect()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); Presence.disconnect() }
    }
    // Refresh the account for all signed-in members, including those awaiting
    // verification. Refresh immediately on foreground, then every 30 seconds.
    // Presence itself remains gated by verification and App Lock.
    val sessionActive = vm.phase.value == SessionPhase.SignedIn
    LaunchedEffect(lifecycleOwner, sessionActive) {
        if (sessionActive) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching { vm.refresh() }
                delay(30_000)
            }
        }
    }
    NightBackground {
        when {
            vm.phase.value == SessionPhase.Loading -> LaunchScreen()
            vm.phase.value == SessionPhase.SignedOut -> AuthScreen(vm)
            locked -> LockScreen(authenticating, authError, unlock)
            !vm.canEnter -> VerificationScreen(vm)
            else -> MainTabs(vm, lockEnabled, setLock)
        }
        vm.error.value?.let { message -> AlertDialog(onDismissRequest={vm.error.value=null}, confirmButton={TextButton(onClick={vm.error.value=null}){Text("OK")}}, title={Text("AFTRHRS")}, text={Text(message)}) }
    }
}

@Composable private fun LaunchScreen() = Column(Modifier.fillMaxSize(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
    Image(painterResource(R.drawable.crescent_logo), "AFTRHRS", Modifier.fillMaxWidth(.65f), contentScale=ContentScale.Fit); Spacer(Modifier.height(20.dp)); CircularProgressIndicator(color=HotPink)
}

@Composable private fun LockScreen(busy: Boolean, error: String?, unlock: () -> Unit) = Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
    Image(painterResource(R.drawable.crescent_logo), "AFTRHRS", Modifier.fillMaxWidth(.6f)); Icon(Icons.Default.Lock, null, tint=Lavender, modifier=Modifier.size(48.dp)); Spacer(Modifier.height(16.dp)); Text("AFTRHRS is locked", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold); Text("Unlock with biometrics or your device credential to continue.", color=Muted, textAlign=TextAlign.Center); Spacer(Modifier.height(24.dp)); NeonButton(if (busy) "Authenticating…" else "Unlock AFTRHRS", !busy, unlock); error?.let{Text(it,color=MaterialTheme.colorScheme.error, modifier=Modifier.padding(top=12.dp))}
}

@Composable private fun AuthScreen(vm: AppViewModel) {
    var join by remember { mutableStateOf(false) }; var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var birth by remember { mutableStateOf(LocalDate.now().minusYears(25).toString()) }; var consent by remember { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    var recoveryEmail by remember { mutableStateOf("") }
    var recoveryBusy by remember { mutableStateOf(false) }
    var recoverySent by remember { mutableStateOf(false) }
    var recoveryError by remember { mutableStateOf<String?>(null) }
    val recoveryScope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp)); Image(painterResource(R.drawable.wordmark), "AFTRHRS", Modifier.fillMaxWidth().height(120.dp), contentScale=ContentScale.Fit); Text("The night starts here.", color=Lavender, style=MaterialTheme.typography.titleLarge); Spacer(Modifier.height(24.dp))
        if (recovering) {
            Text("Reset your password", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Enter your account email. If an active account exists, we'll send a secure password reset link. The link expires after 30 minutes.", color=Muted, textAlign=TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(recoveryEmail, { recoveryEmail=it; recoveryError=null }, label={Text("Email address")}, singleLine=true, modifier=Modifier.fillMaxWidth())
            recoveryError?.let { Text(it, color=MaterialTheme.colorScheme.error, modifier=Modifier.padding(top=8.dp)) }
            if (recoverySent) Text("If an active account exists for that address, check your inbox for a reset link. Also check your spam folder.", color=Lavender, modifier=Modifier.padding(top=12.dp))
            Spacer(Modifier.height(16.dp))
            NeonButton(if (recoveryBusy) "Sending…" else "Send reset link", !recoveryBusy && android.util.Patterns.EMAIL_ADDRESS.matcher(recoveryEmail.trim()).matches()) {
                recoveryBusy=true
                recoveryError=null
                recoveryScope.launch {
                    try {
                        ApiClient.request("auth/password/forgot", HttpMethod.Post, obj("email" to recoveryEmail.trim()))
                        recoverySent=true
                    } catch (e: Exception) {
                        recoveryError=e.message ?: "Unable to request a reset link. Please try again."
                    } finally { recoveryBusy=false }
                }
            }
            TextButton(onClick={ recovering=false; recoveryError=null; recoverySent=false }) { Text("Back to sign in") }
            return@Column
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { SegmentedButton(!join,{join=false},SegmentedButtonDefaults.itemShape(0,2)){Text("Sign in")}; SegmentedButton(join,{join=true},SegmentedButtonDefaults.itemShape(1,2)){Text("Join")} }
        Spacer(Modifier.height(18.dp)); Column(Modifier.fillMaxWidth().glassCard(), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(join){ OutlinedTextField(name,{name=it},label={Text("Display name")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(birth,{birth=it},label={Text("Date of birth (YYYY-MM-DD)")},modifier=Modifier.fillMaxWidth()) }
            OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true,modifier=Modifier.fillMaxWidth()); OutlinedTextField(password,{password=it},label={Text("Password")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            if(join) Row(verticalAlignment=Alignment.Top) { Checkbox(consent,{consent=it}); Text("I confirm that I am at least 18 and accept the AFTRHRS End User Licence Agreement.", color=Muted, style=MaterialTheme.typography.bodySmall, modifier=Modifier.padding(top=12.dp)) }
        }
        if (!join) TextButton(onClick={ recoveryEmail=email; recovering=true; recoverySent=false; recoveryError=null }) { Text("Forgot password?") }
        Spacer(Modifier.height(20.dp)); NeonButton(if(join) "Create account" else "Enter AFTRHRS", email.isNotBlank() && password.length>=8 && (!join || (name.length>=2 && consent))) { if(join) vm.register(email,password,name,"${birth}T00:00:00Z",consent) else vm.login(email,password) }; Spacer(Modifier.height(18.dp)); Text("Adults only · Be real · Be respectful", color=Muted, style=MaterialTheme.typography.bodySmall)
    }
}

private data class VerificationStep(val id:String,val slug:String,val title:String,val help:String)
private val verificationSteps=listOf(VerificationStep("ID_FRONT","id-front","Front of your ID","Keep every edge and detail visible."),VerificationStep("ID_BACK","id-back","Back of your ID","Turn the same document over and photograph the back."),VerificationStep("SELFIE_WITH_ID","selfie-with-id","Selfie holding your ID","Hold the front beside your face so both are clear."))

@Composable private fun VerificationScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("NOT_STARTED") }
    var reviewNote by remember { mutableStateOf<String?>(null) }
    var captured by remember { mutableStateOf(setOf<String>()) }
    var active by remember { mutableStateOf<VerificationStep?>(null) }
    var photo by remember { mutableStateOf<File?>(null) }
    var submitting by remember { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photo?.let { file ->
            active?.let { step ->
                vm.runTask {
                    ApiClient.upload("verification/evidence/${step.slug}", file)
                    captured = captured + step.id
                }
            }
        }
    }
    fun open(step: VerificationStep) {
        active = step
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        photo = File(dir, "verify-${step.slug}.jpg")
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", photo!!))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) active?.let(::open)
        else vm.error.value = "Camera access is required for identity verification."
    }

    // Keep the gate in sync even while the member is unverified. The root presence
    // refresh is intentionally disabled for members who cannot enter the app yet.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val data = ApiClient.request("verification/status").jsonObject
                status = data["status"]?.jsonPrimitive?.contentOrNull ?: "NOT_STARTED"
                reviewNote = data["note"]?.jsonPrimitive?.contentOrNull
                captured = data["evidence"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }
                    ?.toSet() ?: emptySet()
                if (status == "APPROVED") {
                    vm.refresh()
                    break
                }
            } catch (e: Exception) {
                // Transient network failures should not discard the captured evidence.
                if (status == "NOT_STARTED") vm.error.value = e.message ?: "Unable to check verification status."
            }
            delay(15_000)
        }
    }

    val awaitingReview = status == "PENDING" || status == "REVIEW"
    val rejected = status == "REJECTED"
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painterResource(R.drawable.crescent_logo), "AFTRHRS", Modifier.fillMaxWidth(.55f))
        Text(
            when {
                awaitingReview -> "Verification pending"
                rejected -> "Verification needs attention"
                else -> "Verify to enter AFTRHRS"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            when {
                awaitingReview -> "Your identity photos are awaiting human review. This screen checks for approval automatically."
                rejected -> "Your previous submission was not approved. Review the reason below, retake your photos and submit again."
                else -> "Take three live camera photos for manual identity review. Screenshots and document scans are not accepted."
            },
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(12.dp)
        )
        if (rejected) {
            Text(
                reviewNote?.takeIf { it.isNotBlank() } ?: "Please capture clear, readable photos and submit them again.",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
        }
        if (!awaitingReview) {
            verificationSteps.forEachIndexed { i, step ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).glassCard(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${i + 1}", color = Lavender, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.title, fontWeight = FontWeight.Bold)
                        Text(step.help, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    ElectricButton(
                        onClick = {
                            active = step
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open(step)
                            else permission.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.width(104.dp)
                    ) { Text(if (step.id in captured) "Retake" else "Camera") }
                }
            }
            Spacer(Modifier.height(14.dp))
            NeonButton(
                if (submitting) "Submitting…" else "Submit for review",
                !submitting && captured.containsAll(verificationSteps.map { it.id })
            ) {
                submitting = true
                vm.runTask {
                    try {
                        ApiClient.request("verification/submit", HttpMethod.Post)
                        status = "PENDING"
                        reviewNote = null
                    } finally { submitting = false }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { vm.logout() }) { Text("Sign out") }
    }
}

@Composable
private fun MainTabs(vm: AppViewModel, lockEnabled: Boolean, setLock: (Boolean) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var chatsReselect by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.pendingConversation.value, vm.pendingGroup.value) {
        if (vm.pendingConversation.value != null || vm.pendingGroup.value != null) tab = 2
    }
    BackHandler(enabled = tab != 0) { tab = 0 }

    val tabs = listOf("The Grid", "Likes", "Chats", "Premium", "Me")
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = androidx.compose.ui.graphics.Color.White,
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(0xCC493545),
                        androidx.compose.foundation.shape.RoundedCornerShape(36.dp)
                    )
                    .border(
                        1.dp,
                        androidx.compose.ui.graphics.Color.White.copy(alpha = 0.28f),
                        androidx.compose.foundation.shape.RoundedCornerShape(36.dp)
                    )
                    .padding(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, label ->
                    val active = tab == index
                    val tint = if (active) Lavender else androidx.compose.ui.graphics.Color.White
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (active) Electric.copy(alpha = 0.23f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.foundation.shape.RoundedCornerShape(30.dp)
                            )
                            .clickable { if (index == 2 && tab == 2) chatsReselect++ else tab = index }
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val symbol = when (index) {
                            0 -> Icons.Default.AutoAwesome
                            1 -> Icons.Default.Favorite
                            2 -> Icons.Default.ChatBubble
                            3 -> Icons.Default.Diamond
                            else -> Icons.Default.AccountCircle
                        }
                        if (index == 2 && vm.unread.intValue > 0) {
                            BadgedBox(badge = { Badge { Text(vm.unread.intValue.toString()) } }) {
                                Icon(symbol, contentDescription = label, tint = tint, modifier = Modifier.size(27.dp))
                            }
                        } else {
                            Icon(symbol, contentDescription = label, tint = tint, modifier = Modifier.size(27.dp))
                        }
                        Text(
                            label,
                            color = tint,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> DiscoverScreen(vm)
                1 -> LikesScreen(vm)
                2 -> InboxScreen(vm, chatsReselect)
                3 -> PremiumScreen(vm)
                else -> MeScreen(vm, lockEnabled, setLock)
            }
        }
    }
}
