package com.aegismed.app.notify

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aegismed.app.R

object LowStockNotifier {
    fun post(context: Context, medName: String, dosesRemaining: Int) {
        if (!DoseAlarmReceiver.hasNotificationPermission(context)) return
        val n = NotificationCompat.Builder(context, NotificationChannels.SYSTEM)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle(context.getString(R.string.notif_low_stock, medName, dosesRemaining))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(DoseAlarmReceiver.NOTIF_BASE + 7000 + (medName.hashCode() % 1000), n)
        } catch (_: SecurityException) {
        }
    }
}
