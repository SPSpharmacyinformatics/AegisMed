package com.aegismed.app.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aegismed.app.MainActivity
import com.aegismed.app.R
import com.aegismed.app.data.db.AegisDatabase
import com.aegismed.app.domain.DoseStatus
import com.aegismed.app.domain.ScheduleEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class EscalationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val medId = inputData.getLong(AlarmPlanner.EXTRA_MED_ID, -1)
        val scheduledFor = inputData.getLong(AlarmPlanner.EXTRA_SCHEDULED_FOR, -1)
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        if (medId <= 0 || scheduledFor <= 0) return Result.success()

        val db = AegisDatabase.get(applicationContext)
        val med = db.medicationDao().byId(medId) ?: return Result.success()

        val windowStart = scheduledFor - ScheduleEngine.DUE_WINDOW_MINUTES * 60_000
        val windowEnd = scheduledFor + ScheduleEngine.LATE_WINDOW_MINUTES * 60_000
        val acked = db.doseLogDao().between(windowStart, windowEnd)
            .any {
                it.medicationId == medId && it.statusOrdinal in setOf(
                    DoseStatus.TAKEN.ordinal, DoseStatus.SKIPPED.ordinal
                )
            }
        if (acked || !med.active) {
            dismissReAlert()
            return Result.success()
        }

        if (attempt == 0) sendCaregiverMessages(med.name, scheduledFor)

        repostReAlert(med.name)

        if (attempt < MAX_ATTEMPTS) {
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                uniqueName(medId, scheduledFor, attempt + 1),
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EscalationWorker>()
                    .setInitialDelay(RETRY_MINUTES, TimeUnit.MINUTES)
                    .setInputData(workDataOf(
                        AlarmPlanner.EXTRA_MED_ID to medId,
                        AlarmPlanner.EXTRA_SCHEDULED_FOR to scheduledFor,
                        KEY_ATTEMPT to attempt + 1
                    ))
                    .build()
            )
        }
        return Result.success()
    }

    private suspend fun sendCaregiverMessages(medName: String, scheduledFor: Long) {
        val contacts = AegisDatabase.get(applicationContext).careContactDao().list()
        if (contacts.isEmpty()) return

        val timeLabel = Instant.ofEpochMilli(scheduledFor)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        val body = applicationContext.getString(
            R.string.escalation_body,
            "someone under your care", medName, timeLabel
        )

        for (c in contacts) {
            try {
                if (c.channelOrdinal != 0) continue
                val granted = ContextCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.SEND_SMS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) continue
                val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    applicationContext.getSystemService(android.telephony.SmsManager::class.java)
                        ?: @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                sm.sendTextMessage(c.address, null, body, null, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun repostReAlert(medName: String) {
        if (!DoseAlarmReceiver.hasNotificationPermission(applicationContext)) return
        val open = PendingIntent.getActivity(
            applicationContext, 13,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(applicationContext, NotificationChannels.CRITICAL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("Unacknowledged critical dose: $medName")
            .setContentText("Open AegisMed now. Caregivers have been alerted.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(RE_ALERT_ID, n)
        } catch (_: SecurityException) {
        }
    }

    private fun dismissReAlert() {
        NotificationManagerCompat.from(applicationContext).cancel(RE_ALERT_ID)
    }

    private fun uniqueName(medId: Long, scheduledFor: Long, attempt: Int): String =
        "esc_${medId}_${scheduledFor / 60000}_a$attempt"

    companion object {
        const val KEY_ATTEMPT = "attempt"
        const val RETRY_MINUTES = 15L
        const val MAX_ATTEMPTS = 5
        const val RE_ALERT_ID = DoseAlarmReceiver.NOTIF_BASE + 9999

        fun kickOff(context: Context, medId: Long, scheduledFor: Long, delayMinutes: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "esc_${medId}_${scheduledFor / 60000}",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EscalationWorker>()
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .setInputData(workDataOf(
                        AlarmPlanner.EXTRA_MED_ID to medId,
                        AlarmPlanner.EXTRA_SCHEDULED_FOR to scheduledFor,
                        KEY_ATTEMPT to 0
                    ))
                    .build()
            )
        }
    }
}
