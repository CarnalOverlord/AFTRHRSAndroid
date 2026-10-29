package za.co.drkflm.afterhours.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import za.co.drkflm.afterhours.BuildConfig
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import za.co.drkflm.afterhours.MainActivity
import za.co.drkflm.afterhours.R
import za.co.drkflm.afterhours.data.ApiClient
import za.co.drkflm.afterhours.data.obj
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PushNotifications {
    private suspend fun token(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    internal fun registration(token: String) = obj(
        "token" to token,
        "platform" to "android",
        "deviceManufacturer" to Build.MANUFACTURER,
        "deviceModel" to Build.MODEL,
        "osVersion" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "appVersion" to BuildConfig.VERSION_NAME,
        "appBuild" to BuildConfig.VERSION_CODE.toString(),
    )

    suspend fun register() {
        val value = token()
        ApiClient.request("push/register", HttpMethod.Post, registration(value))
    }

    suspend fun unregister() {
        val value = token()
        runCatching { ApiClient.request("push/unregister", HttpMethod.Post, obj("token" to value)) }
    }
}

class AftrhrsMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        serviceScope.launch {
            runCatching { ApiClient.request("push/register", HttpMethod.Post, PushNotifications.registration(token)) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"].orEmpty()
        val title = message.notification?.title ?: data["title"] ?: "AFTRHRS"
        val body = message.notification?.body ?: data["body"] ?: "You have a new notification"
        val channel = if (type == "MESSAGE") "messages" else "activity"
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data["conversationId"]?.let { putExtra("conversationId", it) }
            data["groupId"]?.let { putExtra("groupId", it) }
            data["notificationId"]?.let { putExtra("notificationId", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            (data["notificationId"] ?: message.messageId ?: System.currentTimeMillis().toString()).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(if (channel == "messages") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify((data["notificationId"] ?: message.messageId ?: body).hashCode(), notification)
        }
    }
}
