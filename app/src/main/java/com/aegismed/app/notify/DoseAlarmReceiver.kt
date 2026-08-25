package com.aegismed.app.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aegismed.app.MainActivity
import com.aegismed.app.R
import com.aegismed.app.data.db.AegisDatabase
import com.aegismed.app.domain.DoseStatus
import com.aegismed.app.domain.ScheduleEngine
import com.aegismed.app.domain.Tier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DoseAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (!hasNotificationPermission(appContext)) return
        val medId = intent.getLongExtra(AlarmPlanner.EXTRA_MED_ID, -1)
        val scheduledFor = intent.getLongExtra(AlarmPlanner.EXTRA_SCHEDULED_FOR, -1)

        if (medId <= 0 || scheduledFor <= 0) {
            rearmLater(appContext)
            return
        }

        val db = AegisDatabase.get(appContext)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val med = db.medicationDao().byId(medId)
                val windowStart = scheduledFor - ScheduleEngine.DUE_WINDOW_MINUTES * 60_000
                val windowEnd = scheduledFor + ScheduleEngine.LATE_WINDOW_MINUTES * 60_000
                val resolved = med != null && db.doseLogDao().between(windowStart, windowEnd)
                    .any {
                        it.medicationId == medId && it.statusOrdinal in setOf(
                            DoseStatus.TAKEN.ordinal, DoseStatus.SKIPPED.ordinal, DoseStatus.MISSED.ordinal
                        )
                    }

                if (med != null && med.active && !resolved) {
                    when (med.tier) {
                        Tier.CRITICAL -> postCritical(appContext, med.name)
                        Tier.STANDARD -> postStandard(appContext, med.name, scheduledFor)
                        Tier.ELECTIVE -> Unit
                    }
                    if (med.tier == Tier.CRITICAL) {
                        val minutes = com.aegismed.app.util.Settings.escalationMinutes(appContext)
                        EscalationWorker.kickOff(appContext, medId, scheduledFor, minutes.toLong())
                    }
                }
            } finally {
                AlarmPlanner.armNext(appContext)
                pending.finish()
            }
        }
    }

    private fun rearmLater(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { AlarmPlanner.armNext(context) } finally { pending.finish() }
        }
    }

    private fun openIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun postCritical(context: Context, medName: String) {
        val n = NotificationCompat.Builder(context, NotificationChannels.CRITICAL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle(context.getString(R.string.notif_dose_title, medName))
            .setContentText("Critical medication — open to verify and log this dose.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openIntent(context, 12))
            .setFullScreenIntent(openIntent(context, 12), true)
            .build()
        safeNotify(context, NOTIF_BASE + 5000, n)
    }

    private fun postStandard(context: Context, medName: String, scheduledFor: Long) {
        val n = NotificationCompat.Builder(context, NotificationChannels.STANDARD)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle(context.getString(R.string.notif_dose_title, medName))
            .setContentText("Open AegisMed to log this dose.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, 11))
            .build()
        safeNotify(context, NOTIF_BASE + 1000 + (scheduledFor % 10_000).toInt(), n)
    }

    private fun safeNotify(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val NOTIF_BASE = 424200

        fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
