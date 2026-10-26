package za.co.drkflm.afterhours

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import za.co.drkflm.afterhours.data.Network

class AftrhrsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Network.initialize(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
                NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH).apply { description = "New direct and group messages" },
                NotificationChannel("activity", "Likes and activity", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Likes, requests and account activity" }
            ))
        }
    }
}
