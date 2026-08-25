package com.aegismed.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications ORDER BY tierOrdinal ASC, name ASC")
    fun observeAll(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE active = 1 ORDER BY tierOrdinal ASC, name ASC")
    fun observeActive(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE active = 1")
    suspend fun listActive(): List<MedicationEntity>

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun byId(id: Long): MedicationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(med: MedicationEntity): Long

    @Query("UPDATE medications SET active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)
}

@Dao
interface ScheduleRuleDao {
    @Query("SELECT * FROM schedule_rules WHERE medicationId = :medId")
    suspend fun forMedication(medId: Long): List<ScheduleRuleEntity>

    @Query("SELECT * FROM schedule_rules WHERE active = 1")
    suspend fun listActive(): List<ScheduleRuleEntity>

    @Query("SELECT * FROM schedule_rules WHERE active = 1 AND medicationId IN (SELECT id FROM medications WHERE active = 1)")
    suspend fun listForActiveMeds(): List<ScheduleRuleEntity>

    @Query("SELECT * FROM schedule_rules")
    suspend fun all(): List<ScheduleRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: ScheduleRuleEntity): Long

    @Query("UPDATE schedule_rules SET active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("DELETE FROM schedule_rules WHERE medicationId = :medId")
    suspend fun deleteForMedication(medId: Long)
}

@Dao
interface DoseLogDao {
    @Query("SELECT * FROM dose_logs WHERE scheduledFor BETWEEN :from AND :to ORDER BY scheduledFor ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<DoseLogEntity>>

    @Query("SELECT * FROM dose_logs WHERE scheduledFor BETWEEN :from AND :to ORDER BY scheduledFor ASC")
    suspend fun between(from: Long, to: Long): List<DoseLogEntity>

    @Query("SELECT * FROM dose_logs WHERE medicationId = :medId ORDER BY scheduledFor DESC LIMIT :limit")
    suspend fun recentForMedication(medId: Long, limit: Int): List<DoseLogEntity>

    @Query("SELECT * FROM dose_logs WHERE medicationId = :medId ORDER BY takenAt DESC LIMIT 1")
    suspend fun lastTaken(medId: Long): DoseLogEntity?

    @Query("SELECT * FROM dose_logs WHERE medicationId = :medId AND statusOrdinal = 3 ORDER BY takenAt DESC LIMIT 1")
    suspend fun lastConfirmedTaken(medId: Long): DoseLogEntity?

    @Query("SELECT COUNT(*) FROM dose_logs WHERE medicationId = :medId AND statusOrdinal = 3")
    suspend fun countTaken(medId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: DoseLogEntity): Long

    @Update
    suspend fun update(log: DoseLogEntity)

    @Query("SELECT * FROM dose_logs ORDER BY scheduledFor ASC")
    suspend fun all(): List<DoseLogEntity>
}

@Dao
interface AnchorEventDao {
    @Query("SELECT * FROM anchor_events WHERE kindOrdinal = :kind AND dayEpoch = :dayEpoch LIMIT 1")
    suspend fun find(kind: Int, dayEpoch: Long): AnchorEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun mark(event: AnchorEventEntity): Long

    @Query("SELECT MAX(markedAt) FROM anchor_events WHERE kindOrdinal = :kind AND markedAt <= :before")
    suspend fun latestAtOrBefore(kind: Int, before: Long): Long?
}

@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory")
    fun observeAll(): Flow<List<InventoryEntity>>

    @Query("SELECT * FROM inventory WHERE medicationId = :medId")
    suspend fun forMedication(medId: Long): InventoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(inv: InventoryEntity): Long

    @Query("DELETE FROM inventory WHERE medicationId = :medId")
    suspend fun deleteForMedication(medId: Long)

    @Query("SELECT * FROM inventory")
    suspend fun all(): List<InventoryEntity>

    @Query("UPDATE inventory SET unitsOnHand = unitsOnHand - :units, lastRefillAt = lastRefillAt WHERE medicationId = :medId")
    suspend fun decrement(medId: Long, units: Double)
}

@Dao
interface CareContactDao {
    @Query("SELECT * FROM care_contacts")
    fun observeAll(): Flow<List<CareContactEntity>>

    @Query("SELECT * FROM care_contacts")
    suspend fun list(): List<CareContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: CareContactEntity): Long

    @Query("DELETE FROM care_contacts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DrugCacheDao {
    @Query(
        "SELECT * FROM drug_cache WHERE name LIKE '%' || :q || '%' OR synonym LIKE '%' || :q || '%' " +
            "ORDER BY CASE tty WHEN 'SBD' THEN 0 WHEN 'SCD' THEN 1 WHEN 'SBDG' THEN 2 WHEN 'SCDG' THEN 3 " +
            "WHEN 'GPCK' THEN 4 WHEN 'BPCK' THEN 5 WHEN 'PIN' THEN 6 WHEN 'IN' THEN 7 ELSE 8 END LIMIT 12"
    )
    suspend fun search(q: String): List<DrugCacheEntity>

    @Query("SELECT * FROM drug_cache WHERE rxcui = :rxcui LIMIT 1")
    suspend fun byRxcui(rxcui: String): DrugCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DrugCacheEntity>)

    @Query("SELECT COUNT(*) FROM drug_cache")
    suspend fun count(): Int
}

@Dao
interface RemoteInteractionDao {
    @Query("SELECT * FROM remote_interactions ORDER BY severityKey ASC, id ASC")
    suspend fun all(): List<RemoteInteractionEntity>

    @Query("SELECT * FROM remote_interactions ORDER BY severityKey ASC, id ASC")
    fun observeAll(): Flow<List<RemoteInteractionEntity>>

    @Query("DELETE FROM remote_interactions WHERE rxcuiA IN (:ids) OR rxcuiB IN (:ids)")
    suspend fun clearFor(ids: List<String>)

    @Insert
    suspend fun insertAll(items: List<RemoteInteractionEntity>)

    @Query("SELECT COUNT(*) FROM remote_interactions")
    suspend fun count(): Int
}
