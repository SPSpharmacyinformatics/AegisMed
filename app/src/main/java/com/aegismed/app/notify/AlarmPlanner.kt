package com.aegismed.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aegismed.app.data.db.AegisDatabase
import com.aegismed.app.domain.AnchorKind
import com.aegismed.app.domain.DoseStatus
import com.aegismed.app.domain.RuleType
import com.aegismed.app.domain.ScheduleEngine
import com.aegismed.app.domain.Tier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object AlarmPlanner {

    const val EXTRA_MED_ID = "medId"
    const val EXTRA_RULE_ID = "ruleId"
    const val EXTRA_SCHEDULED_FOR = "scheduledFor"

    data class NextSlot(
        val medicationId: Long,
        val ruleId: Long,
        val scheduledFor: Long,
        val tier: Tier
    )

    suspend fun computeNextSlot(context: Context, fromMillis: Long = System.currentTimeMillis()): NextSlot? {
        val db = AegisDatabase.get(context)
        val meds = db.medicationDao().listActive()
        if (meds.isEmpty()) return null
        val rules = db.scheduleRuleDao().listForActiveMeds()
        if (rules.isEmpty()) return null

        val zone = ZoneId.systemDefault()
        val nightActive = com.aegismed.app.util.Settings.isNightRoutine(context)
        var best: NextSlot? = null

        val horizonDays = 3L
        for (dayOffset in 0 until horizonDays) {
            val date: LocalDate = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate().plusDays(dayOffset)
            val markedAnchors = mutableMapOf<AnchorKind, Long>()
            AnchorKind.entries.forEach { kind ->
                db.anchorEventDao().find(kind.ordinal, date.toEpochDay())
                    ?.let { markedAnchors[kind] = it.markedAt }
            }
            val snapshot = ScheduleEngine.AnchorSnapshot(markedAnchors, nightActive)

            for (rule in rules) {
                val med = meds.firstOrNull { it.id == rule.medicationId } ?: continue
                val lastTaken = db.doseLogDao().lastConfirmedTaken(med.id)?.takenAt
                val occurrences = ScheduleEngine.occurrencesForDay(rule, med, date, snapshot, zone)
                for ((scheduled, _) in occurrences) {
                    if (rule.ruleType == RuleType.INTERVAL_HOURS && lastTaken != null && scheduled <= lastTaken) {
                        continue
                    }
                    val effective = if (rule.ruleType == RuleType.INTERVAL_HOURS || rule.minIntervalHours > 0) {
                        ScheduleEngine.applyDriftFloor(scheduled, lastTaken, rule.minIntervalHours).first
                    } else scheduled

                    if (effective <= fromMillis) continue

                    if (alreadyLogged(db, med.id, scheduled)) continue

                    val candidate = NextSlot(med.id, rule.id, effective, med.tier)
                    if (best == null || candidate.scheduledFor < best!!.scheduledFor) best = candidate
                }
                if (best != null && best.scheduledFor < date.atStartOfDay(zone).toInstant().toEpochMilli() + 24 * 3_600_000L) {
                    return best
                }
            }
            if (best != null) return best
        }
        return best
    }

    private suspend fun alreadyLogged(db: AegisDatabase, medId: Long, scheduledFor: Long): Boolean {
        val dayStart = scheduledFor - ScheduleEngine.DUE_WINDOW_MINUTES * 60_000
        val logs = db.doseLogDao().between(dayStart, scheduledFor + ScheduleEngine.LATE_WINDOW_MINUTES * 60_000)
        return logs.any { it.medicationId == medId && it.statusOrdinal != DoseStatus.UPCOMING.ordinal && it.statusOrdinal != DoseStatus.DUE.ordinal && it.statusOrdinal != DoseStatus.LATE.ordinal }
    }

    fun requestCodeFor(medId: Long, scheduledFor: Long): Int =
        ((medId * 100003) xor (scheduledFor / 60_000)).toInt()

    fun scheduleExact(context: Context, slot: NextSlot) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!canScheduleExact(am)) return
        val pi = pending(context, slot)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.scheduledFor, pi)
    }

    fun cancel(context: Context, slot: NextSlot) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, slot))
    }

    private fun canScheduleExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun pending(context: Context, slot: NextSlot): PendingIntent {
        val intent = Intent(context, DoseAlarmReceiver::class.java).apply {
            putExtra(EXTRA_MED_ID, slot.medicationId)
            putExtra(EXTRA_RULE_ID, slot.ruleId)
            putExtra(EXTRA_SCHEDULED_FOR, slot.scheduledFor)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(slot.medicationId, slot.scheduledFor),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    suspend fun armNext(context: Context) {
        val next = computeNextSlot(context) ?: return
        scheduleExact(context, next)
    }
}
