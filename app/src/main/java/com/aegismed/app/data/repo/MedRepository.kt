package com.aegismed.app.data.repo

import android.content.Context
import com.aegismed.app.data.db.AegisDatabase
import com.aegismed.app.data.db.AnchorEventEntity
import com.aegismed.app.data.db.DoseLogEntity
import com.aegismed.app.data.db.InventoryEntity
import com.aegismed.app.data.db.MedicationEntity
import com.aegismed.app.data.db.ScheduleRuleEntity
import com.aegismed.app.domain.AnchorKind
import com.aegismed.app.domain.DoseStatus
import com.aegismed.app.domain.InventoryManager
import com.aegismed.app.domain.RuleType
import com.aegismed.app.domain.ScheduleEngine
import com.aegismed.app.notify.AlarmPlanner
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object MedRepository {

    fun buildWindow(now: Long): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    suspend fun buildPlan(
        context: Context,
        now: Long
    ): List<ScheduleEngine.DoseSlot> {
        val db = AegisDatabase.get(context)
        val meds = db.medicationDao().listActive()
        if (meds.isEmpty()) return emptyList()
        val rules = db.scheduleRuleDao().listForActiveMeds()
        val zone = ZoneId.systemDefault()
        val nightActive = com.aegismed.app.util.Settings.isNightRoutine(context)

        val (windowStart, windowEnd) = buildWindow(now)
        val logs = db.doseLogDao().between(windowStart, windowEnd)
            .filter { it.takenAt != null || it.statusOrdinal == DoseStatus.SKIPPED.ordinal }

        val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dates = listOf(today.minusDays(1), today, today.plusDays(1))

        val slots = mutableListOf<Pair<ScheduleEngine.DoseSlot, DoseLogEntity?>>()

        for (date in dates) {
            val markedAnchors = mutableMapOf<AnchorKind, Long>()
            for (kind in AnchorKind.entries) {
                db.anchorEventDao().find(kind.ordinal, date.toEpochDay())
                    ?.let { markedAnchors[kind] = it.markedAt }
            }
            val snapshot = ScheduleEngine.AnchorSnapshot(markedAnchors, nightActive)

            for (rule in rules) {
                val med = meds.firstOrNull { it.id == rule.medicationId } ?: continue
                for ((scheduled, amount) in ScheduleEngine.occurrencesForDay(rule, med, date, snapshot, zone)) {
                    if (scheduled < windowStart || scheduled > windowEnd) continue

                    val lastTaken = logs.filter { it.medicationId == med.id && it.takenAt != null }
                        .maxOfOrNull { it.takenAt!! }
                    val (effective, drifted) =
                        if (rule.ruleType != RuleType.INTERVAL_HOURS && lastTaken != null &&
                            rule.minIntervalHours > 0 && lastTaken > scheduled
                        ) ScheduleEngine.applyDriftFloor(scheduled, lastTaken, rule.minIntervalHours)
                        else scheduled to false

                    if (effective < windowStart || effective > windowEnd) continue

                    val log = logs.firstOrNull {
                        it.medicationId == med.id &&
                            Math.abs(it.scheduledFor - effective) <= ScheduleEngine.LATE_WINDOW_MINUTES * 60_000
                    }
                    val status = if (log?.takenAt != null) DoseStatus.TAKEN
                    else if (log?.statusOrdinal == DoseStatus.SKIPPED.ordinal) DoseStatus.SKIPPED
                    else ScheduleEngine.statusOf(effective, null, now)

                    slots += ScheduleEngine.DoseSlot(
                        medicationId = med.id,
                        medicationName = med.name,
                        strengthLabel = ScheduleEngine.strengthLabel(med),
                        ruleId = rule.id,
                        scheduledFor = effective,
                        amount = amount,
                        unitLabel = ScheduleEngine.unitLabel(med, amount),
                        tier = med.tier,
                        verificationMode = med.verificationMode,
                        status = status,
                        logId = log?.id,
                        driftAdjusted = drifted
                    ) to log
                }
            }
        }

        return slots.map { it.first }
            .distinctBy { it.medicationId to it.scheduledFor }
            .sortedBy { it.scheduledFor }
    }

    suspend fun logDose(
        context: Context,
        slot: ScheduleEngine.DoseSlot,
        taken: Boolean
    ): Result<Unit> {
        val db = AegisDatabase.get(context)
        return try {
            val now = System.currentTimeMillis()
            if (slot.logId != null) return Result.success(Unit)
            db.doseLogDao().upsert(
                DoseLogEntity(
                    medicationId = slot.medicationId,
                    scheduledFor = slot.scheduledFor,
                    takenAt = if (taken) now else null,
                    statusOrdinal = if (taken) DoseStatus.TAKEN.ordinal else DoseStatus.SKIPPED.ordinal,
                    verifiedViaOrdinal = slot.verificationMode.ordinal,
                    amount = slot.amount
                )
            )
            if (taken) decrementInventory(context, slot)
            AlarmPlanner.armNext(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun decrementInventory(context: Context, slot: ScheduleEngine.DoseSlot) {
        val db = AegisDatabase.get(context)
        val inv = db.inventoryDao().forMedication(slot.medicationId) ?: return
        val newUnits = (inv.unitsOnHand - inv.unitsPerDose * slot.amount).coerceAtLeast(0.0)
        db.inventoryDao().upsert(inv.copy(unitsOnHand = newUnits))
        val remaining = InventoryManager.dosesRemaining(inv.copy(unitsOnHand = newUnits))
        if (newUnits > 0 && remaining <= inv.refillThreshold.toInt()) {
            com.aegismed.app.notify.LowStockNotifier.post(context, slot.medicationName, remaining)
        }
    }

    suspend fun markAnchor(context: Context, kind: AnchorKind): Long {
        val db = AegisDatabase.get(context)
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
        return db.anchorEventDao().mark(
            AnchorEventEntity(
                kindOrdinal = kind.ordinal,
                dayEpoch = day.toEpochDay(),
                markedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveMedication(
        context: Context,
        med: MedicationEntity,
        rule: ScheduleRuleEntity,
        initialUnits: Double?,
        unitsPerDose: Double,
        refillThreshold: Double
    ): Long {
        val db = AegisDatabase.get(context)
        val medId = db.medicationDao().upsert(med)
        db.scheduleRuleDao().deleteForMedication(medId)
        db.scheduleRuleDao().upsert(rule.copy(medicationId = medId))
        if (initialUnits != null) {
            val existing = db.inventoryDao().forMedication(medId)
            db.inventoryDao().upsert(
                existing?.copy(unitsOnHand = initialUnits, unitsPerDose = unitsPerDose, refillThreshold = refillThreshold)
                    ?: InventoryEntity(
                        medicationId = medId,
                        unitsOnHand = initialUnits,
                        unitsPerDose = unitsPerDose,
                        refillThreshold = refillThreshold
                    )
            )
        } else {
            val existing = db.inventoryDao().forMedication(medId)
            if (existing != null && existing.unitsPerDose != unitsPerDose) {
                db.inventoryDao().upsert(existing.copy(unitsPerDose = unitsPerDose))
            }
        }
        AlarmPlanner.armNext(context)
        return medId
    }

    suspend fun setMedActive(context: Context, medId: Long, active: Boolean) {
        val db = AegisDatabase.get(context)
        db.medicationDao().setActive(medId, active)
        db.scheduleRuleDao().forMedication(medId).forEach {
            db.scheduleRuleDao().setActive(it.id, active)
        }
        AlarmPlanner.armNext(context)
    }

    suspend fun refill(context: Context, medId: Long, unitsAdded: Double) {
        val db = AegisDatabase.get(context)
        val inv = db.inventoryDao().forMedication(medId) ?: return
        db.inventoryDao().upsert(
            inv.copy(unitsOnHand = inv.unitsOnHand + unitsAdded, lastRefillAt = System.currentTimeMillis())
        )
    }

    suspend fun adherenceStats(context: Context, days: Int): Map<LocalDate, Pair<Int, Int>> {
        val db = AegisDatabase.get(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val logs = db.doseLogDao().between(start, end)
        return logs.groupBy {
            Instant.ofEpochMilli(it.scheduledFor).atZone(zone).toLocalDate()
        }.mapValues { (_, v) ->
            val taken = v.count { it.statusOrdinal == DoseStatus.TAKEN.ordinal }
            val missed = v.count { it.statusOrdinal == DoseStatus.MISSED.ordinal }
            val skipped = v.count { it.statusOrdinal == DoseStatus.SKIPPED.ordinal }
            taken to (missed + skipped)
        }
    }

    suspend fun dailyDosesEstimate(context: Context, medId: Long): Double {
        val db = AegisDatabase.get(context)
        val med = db.medicationDao().byId(medId) ?: return 1.0
        val rules = db.scheduleRuleDao().forMedication(medId).filter { it.active }
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone)
        val snapshot = ScheduleEngine.AnchorSnapshot(emptyMap(), com.aegismed.app.util.Settings.isNightRoutine(context))
        var count = 0.0
        for (rule in rules) {
            count += ScheduleEngine.occurrencesForDay(rule, med, date, snapshot, zone).size.toDouble()
        }
        return count.coerceAtLeast(if (rules.isEmpty()) 1.0 else 0.5)
    }

    suspend fun stockListings(context: Context): List<InventoryManager.StockStatus> {
        val db = AegisDatabase.get(context)
        val out = mutableListOf<InventoryManager.StockStatus>()
        for (inv in db.inventoryDao().observeAll().first()) {
            val med = db.medicationDao().byId(inv.medicationId) ?: continue
            out += InventoryManager.statusFor(inv, med, dailyDosesEstimate(context, med.id))
        }
        return out.sortedBy { it.daysLeftEstimate ?: Double.MAX_VALUE }
    }
}
