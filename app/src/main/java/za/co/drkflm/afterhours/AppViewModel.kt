package za.co.drkflm.afterhours

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.http.HttpMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import za.co.drkflm.afterhours.data.*
import za.co.drkflm.afterhours.push.PushNotifications

enum class SessionPhase { Loading, SignedOut, SignedIn }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    var phase = androidx.compose.runtime.mutableStateOf(SessionPhase.Loading); private set
    var user = androidx.compose.runtime.mutableStateOf<JsonObject?>(null); private set
    var entitlements = androidx.compose.runtime.mutableStateOf<JsonObject?>(null); private set
    var error = androidx.compose.runtime.mutableStateOf<String?>(null)
    var unread = androidx.compose.runtime.mutableIntStateOf(0); private set
    var pendingConversation = androidx.compose.runtime.mutableStateOf<String?>(null)
    var pendingGroup = androidx.compose.runtime.mutableStateOf<String?>(null)

    val canEnter: Boolean get() {
        val u = user.value ?: return false
        return u.str("role") in listOf("ADMIN", "SUPER_ADMIN") || u.str("verification") == "APPROVED"
    }

    private val refreshMutex = Mutex()
    private var sessionGeneration = 0L
    init { restore() }
    fun restore() = viewModelScope.launch {
        runCatching { refresh() }.onSuccess { phase.value = SessionPhase.SignedIn; reportPlatformVisit(); refreshUnread(); runCatching { PushNotifications.register() } }
            .onFailure { phase.value = SessionPhase.SignedOut }
    }
    suspend fun refresh() = refreshMutex.withLock {
        val generation = sessionGeneration
        try {
            val latest = ApiClient.request("auth/me").jsonObject
            if (generation == sessionGeneration) {
                user.value = latest
                val subscription = runCatching { ApiClient.request("subscription").jsonObject }.getOrNull()
                if (generation == sessionGeneration) entitlements.value = subscription?.objectOrNull("entitlements")
            }
        } catch (e: ApiException) {
            // Only an explicit authentication failure invalidates the local session.
            // Network failures and temporary server errors must not sign members out.
            if (generation == sessionGeneration && e.message?.contains("Unauthenticated", ignoreCase = true) == true) {
                sessionGeneration++
                user.value = null
                entitlements.value = null
                unread.intValue = 0
                phase.value = SessionPhase.SignedOut
            }
            throw e
        }
    }
    private suspend fun reportPlatformVisit() {
        runCatching { ApiClient.request("platform-visit", HttpMethod.Post, obj("platform" to "android")) }
    }
    fun login(email: String, password: String) = runTask {
        ApiClient.request("auth/login", HttpMethod.Post, obj("email" to email, "password" to password)); refresh(); phase.value = SessionPhase.SignedIn; refreshUnread(); runCatching { PushNotifications.register() }
    }
    fun register(email: String, password: String, name: String, birthDate: String, consent: Boolean) = runTask {
        ApiClient.request("auth/register", HttpMethod.Post, obj("email" to email, "password" to password, "displayName" to name, "dateOfBirth" to birthDate, "consent" to consent)); refresh(); phase.value = SessionPhase.SignedIn; reportPlatformVisit(); runCatching { PushNotifications.register() }
    }
    fun logout() = runTask { sessionGeneration++; runCatching { PushNotifications.unregister() }; runCatching { ApiClient.request("auth/logout", HttpMethod.Post) }; user.value = null; entitlements.value = null; unread.intValue = 0; phase.value = SessionPhase.SignedOut }
    fun routePush(conversationId:String?,groupId:String?){
        when { !conversationId.isNullOrBlank() -> pendingConversation.value=conversationId; !groupId.isNullOrBlank() -> pendingGroup.value=groupId }
    }
    suspend fun refreshUnread() {
        runCatching {
            val direct = viewModelScope.async { ApiClient.request("conversations").jsonArray }
            val groups = viewModelScope.async { ApiClient.request("group-chats").jsonArray }
            unread.intValue = (direct.await() + groups.await()).sumOf { it.jsonObject.int("unreadCount") }
        }
    }
    fun runTask(block: suspend () -> Unit) = viewModelScope.launch { runCatching { block() }.onFailure { error.value = it.message ?: "Something went wrong" } }
}
