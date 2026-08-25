package com.aegismed.app.util

import android.content.Context
import android.net.Uri
import com.aegismed.app.data.db.AegisDatabase
import com.aegismed.app.data.db.AnchorEventEntity
import com.aegismed.app.data.db.CareContactEntity
import com.aegismed.app.data.db.DoseLogEntity
import com.aegismed.app.data.db.InventoryEntity
import com.aegismed.app.data.db.MedicationEntity
import com.aegismed.app.data.db.ScheduleRuleEntity
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupManager {

    private const val MAGIC = "AEGIS1"
    private const val PBKDF2_ITERS = 210_000
    private const val KEY_BITS = 256

    suspend fun exportEncrypted(context: Context, passphrase: CharArray, dest: Uri): Result<Int> {
        return try {
            val db = AegisDatabase.get(context)
            val dump = JSONObject().apply {
                put("medications", toJsonArray(db.medicationDao().observeAll().first()))
                put("rules", toJsonArrayRules(db.scheduleRuleDao().listActive() + db.scheduleRuleDao().all()))
                put("dose_logs", toJsonArrayLogs(db.doseLogDao().all()))
                put("inventory", toJsonArrayInv(db.inventoryDao().all()))
                put("contacts", toJsonArrayContacts(db.careContactDao().list()))
            }
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val plaintext = dump.toString().toByteArray(Charsets.UTF_8)
            val ciphertext = cipher.doFinal(plaintext)

            val opened = context.contentResolver.openOutputStream(dest)
            if (opened == null) {
                return Result.failure(IllegalStateException("Cannot open destination"))
            }
            opened.use { os ->
                os.write(MAGIC.toByteArray(Charsets.US_ASCII))
                os.write(salt)
                os.write(iv)
                val len = plaintext.size
                os.write((len ushr 24) and 0xFF)
                os.write((len ushr 16) and 0xFF)
                os.write((len ushr 8) and 0xFF)
                os.write(len and 0xFF)
                os.write(ciphertext)
            }
            Result.success(plaintext.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreEncrypted(context: Context, passphrase: CharArray, src: Uri): Result<Int> {
        return try {
            val data = context.contentResolver.openInputStream(src)?.use { it.readBytes() }
                ?: return Result.failure(IllegalStateException("Cannot read file"))
            val magicLen = MAGIC.length
            require(data.copyOfRange(0, magicLen).toString(Charsets.US_ASCII) == MAGIC) { "Not an AegisMed backup" }
            var off = magicLen
            val salt = data.copyOfRange(off, off + 16); off += 16
            val iv = data.copyOfRange(off, off + 12); off += 12
            val len = ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or
                ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
            off += 4
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(data.copyOfRange(off, off + len))
            val count = restoreDump(context, JSONObject(String(plain, Charsets.UTF_8)))
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERS, KEY_BITS)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = f.generateSecret(spec).encoded
        spec.clearPassword()
        return key
    }

    private suspend fun restoreDump(context: Context, dump: JSONObject): Int {
        val db = AegisDatabase.get(context)
        val idMap = HashMap<Long, Long>()
        forEach(dump.getJSONArray("medications")) { o ->
            val oldId = o.getLong("id")
            val newId = db.medicationDao().upsert(
                MedicationEntity(
                    name = o.getString("name"),
                    strengthValue = o.optDouble("strengthValue", Double.NaN).takeUnless { it.isNaN() },
                    strengthUnit = o.optString("strengthUnit").ifBlank { null },
                    form = o.optString("form").ifBlank { null },
                    tierOrdinal = o.getInt("tierOrdinal"),
                    verificationModeOrdinal = o.getInt("verificationModeOrdinal"),
                    barcode = o.optString("barcode").ifBlank { null },
                    nfcTagIdHex = o.optString("nfcTagIdHex").ifBlank { null },
                    rxcui = o.opt("rxcui")?.toString()?.ifBlank { null },
                    sigNotes = o.optString("sigNotes").ifBlank { null },
                    active = o.getBoolean("active"),
                    createdAt = o.getLong("createdAt")
                )
            )
            idMap[oldId] = newId
        }
        forEach(dump.getJSONArray("rules")) { o ->
            val medId = idMap[o.getLong("medicationId")]
            if (medId != null) {
                db.scheduleRuleDao().upsert(
                    ScheduleRuleEntity(
                        medicationId = medId,
                        ruleTypeOrdinal = o.getInt("ruleTypeOrdinal"),
                        active = o.getBoolean("active"),
                        timesJson = o.optString("timesJson").orEmpty(),
                        intervalHours = o.optDouble("intervalHours", Double.NaN).takeUnless { it.isNaN() },
                        minIntervalHours = o.getDouble("minIntervalHours"),
                        anchorKindOrdinal = if (o.isNull("anchorKindOrdinal")) null else o.getInt("anchorKindOrdinal"),
                        offsetMinutes = o.getInt("offsetMinutes"),
                        startDayEpoch = o.getLong("startDayEpoch"),
                        dayParity = if (o.isNull("dayParity")) null else o.getInt("dayParity"),
                        onDays = if (o.isNull("onDays")) null else o.getInt("onDays"),
                        offDays = if (o.isNull("offDays")) null else o.getInt("offDays"),
                        taperStepsJson = o.optString("taperStepsJson").ifBlank { null },
                        routineProfileOrdinal = o.getInt("routineProfileOrdinal")
                    )
                )
            }
        }
        forEach(dump.getJSONArray("dose_logs")) { o ->
            val medId = idMap[o.getLong("medicationId")]
            if (medId != null) {
                db.doseLogDao().upsert(
                    DoseLogEntity(
                        medicationId = medId,
                        scheduledFor = o.getLong("scheduledFor"),
                        takenAt = if (o.isNull("takenAt")) null else o.getLong("takenAt"),
                        statusOrdinal = o.getInt("statusOrdinal"),
                        verifiedViaOrdinal = o.getInt("verifiedViaOrdinal"),
                        amount = o.getDouble("amount")
                    )
                )
            }
        }
        forEach(dump.getJSONArray("inventory")) { o ->
            val medId = idMap[o.getLong("medicationId")]
            if (medId != null) {
                db.inventoryDao().upsert(
                    InventoryEntity(
                        medicationId = medId,
                        unitsOnHand = o.getDouble("unitsOnHand"),
                        unitsPerDose = o.getDouble("unitsPerDose"),
                        refillThreshold = o.getDouble("refillThreshold"),
                        lastRefillAt = if (o.isNull("lastRefillAt")) null else o.getLong("lastRefillAt")
                    )
                )
            }
        }
        forEach(dump.getJSONArray("contacts")) { o ->
            db.careContactDao().upsert(
                CareContactEntity(
                    name = o.getString("name"),
                    channelOrdinal = o.getInt("channelOrdinal"),
                    address = o.getString("address")
                )
            )
        }
        return idMap.size
    }

    private inline fun forEach(arr: JSONArray, block: (JSONObject) -> Unit) {
        for (i in 0 until arr.length()) {
            try {
                block(arr.getJSONObject(i))
            } catch (_: Exception) {
            }
        }
    }

    private fun toJsonArray(items: List<MedicationEntity>): JSONArray = JSONArray().apply {
        items.forEach { m ->
            put(JSONObject().apply {
                put("id", m.id); put("name", m.name)
                m.strengthValue?.let { put("strengthValue", it) }
                put("strengthUnit", m.strengthUnit ?: "")
                put("form", m.form ?: "")
                put("tierOrdinal", m.tierOrdinal)
                put("verificationModeOrdinal", m.verificationModeOrdinal)
                put("barcode", m.barcode ?: ""); put("nfcTagIdHex", m.nfcTagIdHex ?: "")
                put("rxcui", m.rxcui ?: "")
                put("sigNotes", m.sigNotes ?: ""); put("active", m.active); put("createdAt", m.createdAt)
            })
        }
    }

    private fun toJsonArrayRules(items: List<ScheduleRuleEntity>): JSONArray = JSONArray().apply {
        items.forEach { r ->
            put(JSONObject().apply {
                put("medicationId", r.medicationId); put("ruleTypeOrdinal", r.ruleTypeOrdinal)
                put("active", r.active); put("timesJson", r.timesJson)
                r.intervalHours?.let { put("intervalHours", it) }
                put("minIntervalHours", r.minIntervalHours)
                if (r.anchorKindOrdinal != null) put("anchorKindOrdinal", r.anchorKindOrdinal) else put("anchorKindOrdinal", JSONObject.NULL)
                put("offsetMinutes", r.offsetMinutes); put("startDayEpoch", r.startDayEpoch)
                if (r.dayParity != null) put("dayParity", r.dayParity) else put("dayParity", JSONObject.NULL)
                if (r.onDays != null) put("onDays", r.onDays) else put("onDays", JSONObject.NULL)
                if (r.offDays != null) put("offDays", r.offDays) else put("offDays", JSONObject.NULL)
                put("taperStepsJson", r.taperStepsJson ?: ""); put("routineProfileOrdinal", r.routineProfileOrdinal)
            })
        }
    }

    private fun toJsonArrayLogs(items: List<DoseLogEntity>): JSONArray = JSONArray().apply {
        items.forEach { l ->
            put(JSONObject().apply {
                put("medicationId", l.medicationId); put("scheduledFor", l.scheduledFor)
                if (l.takenAt != null) put("takenAt", l.takenAt) else put("takenAt", JSONObject.NULL)
                put("statusOrdinal", l.statusOrdinal); put("verifiedViaOrdinal", l.verifiedViaOrdinal)
                put("amount", l.amount)
            })
        }
    }

    private fun toJsonArrayInv(items: List<InventoryEntity>): JSONArray = JSONArray().apply {
        items.forEach { i ->
            put(JSONObject().apply {
                put("medicationId", i.medicationId); put("unitsOnHand", i.unitsOnHand)
                put("unitsPerDose", i.unitsPerDose); put("refillThreshold", i.refillThreshold)
                if (i.lastRefillAt != null) put("lastRefillAt", i.lastRefillAt) else put("lastRefillAt", JSONObject.NULL)
            })
        }
    }

    private fun toJsonArrayContacts(items: List<CareContactEntity>): JSONArray = JSONArray().apply {
        items.forEach { c ->
            put(JSONObject().apply {
                put("name", c.name); put("channelOrdinal", c.channelOrdinal); put("address", c.address)
            })
        }
    }
}
