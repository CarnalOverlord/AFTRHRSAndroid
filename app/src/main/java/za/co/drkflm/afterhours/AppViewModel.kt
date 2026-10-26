package za.co.drkflm.afterhours

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.http.HttpMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import za.co.drkflm.afterhours.data.*

enum class SessionPhase { Loading, SignedOut, SignedIn }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    var phase = androidx.compose.runtime.mutableStateOf(SessionPhase.Loading); private set
    var user = androidx.compose.runtime.mutableStateOf<JsonObject?>(null); private set
    var error = androidx.compose.runtime.mutableStateOf<String?>(null)
    var unread = androidx.compose.runtime.mutableIntStateOf(0); private set
    var pendingConversation = androidx.compose.runtime.mutableStateOf<String?>(null)
    var pendingGroup = androidx.compose.runtime.mutableStateOf<String?>(null)

    val canEnter: Boolean get() {
        val u = user.value ?: return false
        return u.str("role") in listOf("ADMIN", "SUPER_ADMIN") || u.str("verification") == "APPROVED"
    }

    init { restore() }
    fun restore() = viewModelScope.launch {
        runCatching { refresh() }.onSuccess { phase.value = SessionPhase.SignedIn; refreshUnread() }
            .onFailure { phase.value = SessionPhase.SignedOut }
    }
    suspend fun refresh() { user.value = ApiClient.request("auth/me").jsonObject }
    fun login(email: String, password: String) = runTask {
        ApiClient.request("auth/login", HttpMethod.Post, obj("email" to email, "password" to password)); refresh(); phase.value = SessionPhase.SignedIn; refreshUnread()
    }
    fun register(email: String, password: String, name: String, birthDate: String, consent: Boolean) = runTask {
        ApiClient.request("auth/register", HttpMethod.Post, obj("email" to email, "password" to password, "displayName" to name, "dateOfBirth" to birthDate, "consent" to consent)); refresh(); phase.value = SessionPhase.SignedIn
    }
    fun logout() = runTask { runCatching { ApiClient.request("auth/logout", HttpMethod.Post) }; user.value = null; unread.intValue = 0; phase.value = SessionPhase.SignedOut }
    suspend fun refreshUnread() {
        runCatching {
            val direct = viewModelScope.async { ApiClient.request("conversations").jsonArray }
            val groups = viewModelScope.async { ApiClient.request("group-chats").jsonArray }
            unread.intValue = (direct.await() + groups.await()).sumOf { it.jsonObject.int("unreadCount") }
        }
    }
    fun runTask(block: suspend () -> Unit) = viewModelScope.launch { runCatching { block() }.onFailure { error.value = it.message ?: "Something went wrong" } }
}
