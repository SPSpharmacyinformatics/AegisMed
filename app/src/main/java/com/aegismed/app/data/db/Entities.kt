package com.aegismed.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aegismed.app.domain.AnchorKind
import com.aegismed.app.domain.RuleType
import com.aegismed.app.domain.RoutineProfile
import com.aegismed.app.domain.Tier
import com.aegismed.app.domain.VerificationMode

@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val strengthValue: Double? = null,
    val strengthUnit: String? = null,
    val form: String? = null,
    val tierOrdinal: Int = Tier.STANDARD.ordinal,
    val verificationModeOrdinal: Int = VerificationMode.TAP.ordinal,
    val barcode: String? = null,
    val nfcTagIdHex: String? = null,
    val sigNotes: String? = null,
    val rxcui: String? = null,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    val tier: Tier get() = Tier.entries[tierOrdinal]
    val verificationMode: VerificationMode get() = VerificationMode.entries[verificationModeOrdinal]
}

@Entity(tableName = "schedule_rules")
data class ScheduleRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val ruleTypeOrdinal: Int,
    val active: Boolean = true,
    val timesJson: String = "",
    val intervalHours: Double? = null,
    val minIntervalHours: Double = 4.0,
    val anchorKindOrdinal: Int? = null,
    val offsetMinutes: Int = 0,
    val startDayEpoch: Long = 0,
    val dayParity: Int? = null,
    val onDays: Int? = null,
    val offDays: Int? = null,
    val taperStepsJson: String? = null,
    val routineProfileOrdinal: Int = RoutineProfile.ANY.ordinal
) {
    val ruleType: RuleType get() = RuleType.entries[ruleTypeOrdinal]
    val anchorKind: AnchorKind? get() = anchorKindOrdinal?.let { AnchorKind.entries[it] }
    val routineProfile: RoutineProfile get() = RoutineProfile.entries[routineProfileOrdinal]
}

@Entity(tableName = "dose_logs")
data class DoseLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val scheduledFor: Long,
    val takenAt: Long? = null,
    val statusOrdinal: Int,
    val verifiedViaOrdinal: Int = 0,
    val amount: Double = 1.0
)

@Entity(tableName = "anchor_events")
data class AnchorEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kindOrdinal: Int,
    val dayEpoch: Long,
    val markedAt: Long
)

@Entity(tableName = "inventory")
data class InventoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val unitsOnHand: Double = 0.0,
    val unitsPerDose: Double = 1.0,
    val refillThreshold: Double = 5.0,
    val lastRefillAt: Long? = null
)

@Entity(tableName = "care_contacts")
data class CareContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val channelOrdinal: Int = 0,
    val address: String
)

@Entity(tableName = "drug_cache")
data class DrugCacheEntity(
    @PrimaryKey val rxcui: String,
    val name: String,
    val synonym: String?,
    val tty: String,
    val fetchedAt: Long
)

@Entity(tableName = "remote_interactions")
data class RemoteInteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rxcuiA: String,
    val nameA: String,
    val rxcuiB: String,
    val nameB: String,
    val severityKey: String,
    val severityRaw: String,
    val description: String,
    val source: String?,
    val fetchedAt: Long
)
