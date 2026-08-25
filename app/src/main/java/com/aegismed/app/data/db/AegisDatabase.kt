package com.aegismed.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        MedicationEntity::class,
        ScheduleRuleEntity::class,
        DoseLogEntity::class,
        AnchorEventEntity::class,
        InventoryEntity::class,
        CareContactEntity::class,
        DrugCacheEntity::class,
        RemoteInteractionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AegisDatabase : RoomDatabase() {
    abstract fun medicationDao(): MedicationDao
    abstract fun scheduleRuleDao(): ScheduleRuleDao
    abstract fun doseLogDao(): DoseLogDao
    abstract fun anchorEventDao(): AnchorEventDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun careContactDao(): CareContactDao
    abstract fun drugCacheDao(): DrugCacheDao
    abstract fun remoteInteractionDao(): RemoteInteractionDao

    companion object {
        @Volatile private var instance: AegisDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medications ADD COLUMN rxcui TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drug_cache` (" +
                        "`rxcui` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`synonym` TEXT, " +
                        "`tty` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`rxcui`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remote_interactions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`rxcuiA` TEXT NOT NULL, " +
                        "`nameA` TEXT NOT NULL, " +
                        "`rxcuiB` TEXT NOT NULL, " +
                        "`nameB` TEXT NOT NULL, " +
                        "`severityKey` TEXT NOT NULL, " +
                        "`severityRaw` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`source` TEXT, " +
                        "`fetchedAt` INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): AegisDatabase =
            instance ?: synchronized(this) {
                System.loadLibrary("sqlcipher")
                val passphrase = com.aegismed.app.data.SecretVault.dbPassphrase(context.applicationContext)
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    AegisDatabase::class.java,
                    "aegismed.db"
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase, null, false))
                    .addMigrations(MIGRATION_1_2)
                    .build()
                instance = db
                db
            }
    }
}
