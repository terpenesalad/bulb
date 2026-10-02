package app.halo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object Notifications {
    const val CHANNEL_EFFECTS = "effects"
    const val CHANNEL_SCHEDULES = "schedules"
    const val ID_EFFECT = 11
    const val ID_SCHEDULE = 12
    const val ID_SCHEDULE_FAILED = 13

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EFFECTS, "Light effects", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while an effect is playing on your light"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SCHEDULES, "Schedules", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a schedule (like a sunrise wake-up) is running, or if one couldn't reach the light"
                setShowBadge(false)
            },
        )
    }
}
