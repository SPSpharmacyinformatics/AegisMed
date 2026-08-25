package com.aegismed.app.domain

import com.aegismed.app.data.db.DoseLogEntity
import com.aegismed.app.data.db.MedicationEntity
import com.aegismed.app.data.db.ScheduleRuleEntity
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object ScheduleEngine {

    const val DUE_WINDOW_MINUTES: Long = 30
    const val LATE_WINDOW_MINUTES: Long = 120
    private const val MS_PER_MINUTE = 60_000L
    private const val MS_PER_HOUR = 3_600_000L

    data class DoseSlot(
        val medicationId: Long,
        val medicationName: String,
        val strengthLabel: String,
        val ruleId: Long,
        val scheduledFor: Long,
        val amount: Double,
        val unitLabel: String,
        val tier: Tier,
        val verificationMode: VerificationMode,
        val status: DoseStatus,
        val logId: Long? = null,
        val driftAdjusted: Boolean = false
    )

    data class AnchorSnapshot(
        val markedAnchors: Map<AnchorKind, Long>,
        val activeRoutineIsNight: Boolean
    )

    fun parseTimes(json: String): List<LocalTime> =
        runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { LocalTime.parse(arr.getString(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())

    data class TaperStep(val weekOffset: Int, val dose: Double)

    fun parseTaperSteps(json: String?): List<TaperStep> =
        if (json.isNullOrBlank()) emptyList()
        else runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TaperStep(o.optInt("weekOffset", 0), o.optDouble("dose", 1.0))
            }.sortedByDescending { it.weekOffset }
        }.getOrDefault(emptyList())

    fun encodeTaperSteps(steps: List<TaperStep>): String {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(org.json.JSONObject().put("weekOffset", s.weekOffset).put("dose", s.dose))
        }
        return arr.toString()
    }

    fun occursOn(rule: ScheduleRuleEntity, dayEpoch: Long): Boolean {
        return when (rule.ruleType) {
            RuleType.ALTERNATING_DAYS ->
                Math.floorMod(dayEpoch - rule.startDayEpoch, 2L) == (rule.dayParity ?: 0).toLong()
            RuleType.CYCLE -> {
                val on = rule.onDays ?: return true
                val off = rule.offDays ?: 0
                val cycle = (on + off).coerceAtLeast(1)
                Math.floorMod(dayEpoch - rule.startDayEpoch, cycle.toLong()) < on.toLong()
            }
            else -> true
        }
    }

    fun doseAmountFor(rule: ScheduleRuleEntity, med: MedicationEntity, dayEpoch: Long): Double {
        if (rule.ruleType != RuleType.TAPER) return 1.0
        val steps = parseTaperSteps(rule.taperStepsJson)
        if (steps.isEmpty()) return 1.0
        val weeks = ((dayEpoch - rule.startDayEpoch).coerceAtLeast(0)) / 7
        val step = steps.firstOrNull { weeks >= it.weekOffset } ?: TaperStep(0, 1.0)
        return if (step.dose > 0) step.dose / defaultUnitAmount(med) else 1.0
    }

    fun defaultUnitAmount(med: MedicationEntity): Double =
        when (med.form?.lowercase()) {
            "liquid", "drops", "injection" -> 5.0
            else -> 1.0
        }

    fun anchorFallback(anchor: AnchorKind, nightRoutine: Boolean): LocalTime =
        if (nightRoutine) LocalTime.of(anchor.nightFallbackHour % 24, 0)
        else LocalTime.of(anchor.dayFallbackHour, 0)

    fun anchorBase(
        anchor: AnchorKind,
        date: LocalDate,
        snapshot: AnchorSnapshot,
        zone: ZoneId
    ): LocalDateTime {
        val marked = snapshot.markedAnchors[anchor]
        if (marked != null) {
            val d = Instant.ofEpochMilli(marked).atZone(zone)
            if (d.toLocalDate() == date) return d.toLocalDateTime()
        }
        return LocalDateTime.of(date, anchorFallback(anchor, snapshot.activeRoutineIsNight))
    }

    fun occurrencesForDay(
        rule: ScheduleRuleEntity,
        med: MedicationEntity,
        date: LocalDate,
        snapshot: AnchorSnapshot,
        zone: ZoneId
    ): List<Pair<Long, Double>> {
        if (!rule.active || !occursOn(rule, date.toEpochDay())) return emptyList()
        if (!profileMatches(rule.routineProfile, snapshot.activeRoutineIsNight)) return emptyList()

        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val slots = mutableListOf<Pair<Long, Double>>()

        when (rule.ruleType) {
            RuleType.FIXED_TIMES ->
                parseTimes(rule.timesJson).forEach {
                    slots += LocalDateTime.of(date, it).atZone(zone).toInstant().toEpochMilli() to 1.0
                }

            RuleType.INTERVAL_HOURS -> {
                val interval = ((rule.intervalHours ?: 12.0).coerceIn(0.25, 72.0) * MS_PER_HOUR).toLong()
                var t = rule.startDayEpoch * 24 * MS_PER_HOUR
                if (t > dayStart) t = dayStart
                val end = dayStart + 24 * MS_PER_HOUR
                while (t < end) {
                    if (t >= dayStart) slots += t to 1.0
                    t += interval
                }
                if (slots.isEmpty()) slots += dayStart to 1.0
            }

            RuleType.ALTERNATING_DAYS, RuleType.CYCLE ->
                parseTimes(rule.timesJson.ifBlank { "[\"08:00\"]" }).forEach {
                    slots += LocalDateTime.of(date, it).atZone(zone).toInstant().toEpochMilli() to 1.0
                }

            RuleType.TAPER -> {
                val amt = doseAmountFor(rule, med, date.toEpochDay())
                parseTimes(rule.timesJson.ifBlank { "[\"09:00\"]" }).forEach {
                    slots += LocalDateTime.of(date, it).atZone(zone).toInstant().toEpochMilli() to amt
                }
            }

            RuleType.RELATIVE_ANCHOR -> {
                val kind = rule.anchorKind ?: AnchorKind.BREAKFAST
                val base = anchorBase(kind, date, snapshot, zone)
                slots += base.plusMinutes(rule.offsetMinutes.toLong())
                    .atZone(zone).toInstant().toEpochMilli() to 1.0
            }
        }
        return slots.sortedBy { it.first }
    }

    fun profileMatches(profile: RoutineProfile, nightActive: Boolean): Boolean =
        when (profile) {
            RoutineProfile.ANY -> true
            RoutineProfile.DAY -> !nightActive
            RoutineProfile.NIGHT -> nightActive
        }

    fun applyDriftFloor(
        scheduledFor: Long,
        lastTakenAt: Long?,
        minIntervalHours: Double
    ): Pair<Long, Boolean> {
        if (lastTakenAt == null || minIntervalHours <= 0.0) return scheduledFor to false
        val floor = lastTakenAt + (minIntervalHours * MS_PER_HOUR).toLong()
        return if (floor > scheduledFor) floor to true else scheduledFor to false
    }

    fun statusOf(scheduledFor: Long, log: DoseLogEntity?, now: Long): DoseStatus {
        if (log != null && log.statusOrdinal == DoseStatus.TAKEN.ordinal) return DoseStatus.TAKEN
        if (log != null && log.statusOrdinal == DoseStatus.SKIPPED.ordinal) return DoseStatus.SKIPPED
        if (log != null && log.statusOrdinal == DoseStatus.MISSED.ordinal) return DoseStatus.MISSED
        return when {
            now < scheduledFor -> DoseStatus.UPCOMING
            now <= scheduledFor + DUE_WINDOW_MINUTES * MS_PER_MINUTE -> DoseStatus.DUE
            now <= scheduledFor + LATE_WINDOW_MINUTES * MS_PER_MINUTE -> DoseStatus.LATE
            else -> DoseStatus.MISSED
        }
    }

    fun strengthLabel(med: MedicationEntity): String {
        val sv = med.strengthValue ?: return ""
        val v = if (sv == sv.toLong().toDouble()) sv.toLong().toString() else sv.toString()
        return "$v ${med.strengthUnit ?: ""}".trim()
    }

    fun unitLabel(med: MedicationEntity, amount: Double): String {
        val base = when (med.form?.lowercase()) {
            "liquid", "drops" -> "ml"
            "injection" -> "units"
            else -> "tablet${if (amount > 1.0) "s" else ""}"
        }
        return base
    }

    fun prettyAmount(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else "%.1f".format(amount)
}
