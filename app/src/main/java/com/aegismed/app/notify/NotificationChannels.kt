package com.aegismed.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

object NotificationChannels {
    const val CRITICAL = "aegis_critical"
    const val STANDARD = "aegis_standard"
    const val PASSIVE = "aegis_passive"
    const val SYSTEM = "aegis_system"

    fun ensure(context: Context) {
        val nm = NotificationManagerCompat.from(context)

        val critical = NotificationChannel(
            CRITICAL, context.getString(com.aegismed.app.R.string.channel_critical),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen alarms for life-critical doses"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 1200)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val standard = NotificationChannel(
            STANDARD, context.getString(com.aegismed.app.R.string.channel_standard),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }

        val passive = NotificationChannel(
            PASSIVE, context.getString(com.aegismed.app.R.string.channel_passive),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }

        val system = NotificationChannel(
            SYSTEM, context.getString(com.aegismed.app.R.string.channel_system),
            NotificationManager.IMPORTANCE_DEFAULT
        )

        nm.createNotificationChannels(listOf(critical, standard, passive, system))
    }
}
