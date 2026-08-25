package com.aegismed.app.data.repo

import android.content.Context
import com.aegismed.app.data.db.AegisDatabase
import com.aegismed.app.data.db.DrugCacheEntity
import com.aegismed.app.data.db.MedicationEntity
import com.aegismed.app.data.db.RemoteInteractionEntity
import com.aegismed.app.data.remote.OpenFdaService
import com.aegismed.app.data.remote.RxNormService
import com.aegismed.app.util.Settings

object ClinicalLookupRepository {

    data class Enrichment(
        val rxcui: String?,
        val indications: String?,
        val dosage: String?,
        val warnings: String?,
        val drugInteractions: String?,
        val brand: String?,
        val generic: String?,
        val doseForm: String?
    )

    private fun severityKey(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "high" in lower || "contra" in lower -> "major"
            "moderate" in lower -> "moderate"
            else -> "minor"
        }
    }

    suspend fun autofillSearch(context: Context, query: String): List<DrugCacheEntity> {
        val db = AegisDatabase.get(context)
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()

        val cached = db.drugCacheDao().search(q)

        val online = runCatching { Settings.onlineLookupsEnabled(context) }.getOrDefault(true)
        if (!online) return cached

        val remote = RxNormService.searchDrugs(query).getOrDefault(emptyList())
        if (remote.isEmpty()) return cached

        val now = System.currentTimeMillis()
        db.drugCacheDao().upsertAll(
            remote.map { s ->
                DrugCacheEntity(
                    rxcui = s.rxcui,
                    name = s.name,
                    synonym = s.synonym,
                    tty = s.tty,
                    fetchedAt = now
                )
            }
        )
        return remote.map { s ->
            DrugCacheEntity(rxcui = s.rxcui, name = s.name, synonym = s.synonym, tty = s.tty, fetchedAt = now)
        }
    }

    suspend fun resolveRxcui(context: Context, medName: String): String? {
        val db = AegisDatabase.get(context)
        val direct = db.drugCacheDao().search(medName.trim().lowercase())
            .firstOrNull { entry ->
                entry.synonym?.contains(medName.trim(), ignoreCase = true) == true ||
                    entry.name.contains(medName.trim(), ignoreCase = true)
            }
        if (direct != null) return direct.rxcui

        val online = runCatching { Settings.onlineLookupsEnabled(context) }.getOrDefault(true)
        if (!online) return null

        val suggestions = RxNormService.searchDrugs(medName).getOrElse { return null }
        if (suggestions.isNotEmpty()) {
            val now = System.currentTimeMillis()
            db.drugCacheDao().upsertAll(
                suggestions.map {
                    DrugCacheEntity(it.rxcui, it.name, it.synonym, it.tty, now)
                }
            )
            return suggestions.firstOrNull()?.rxcui
        }

        val spelled = RxNormService.spellSuggestions(medName).firstOrNull() ?: return null
        val retry = RxNormService.searchDrugs(spelled).getOrDefault(emptyList())
        if (retry.isEmpty()) return null
        val now = System.currentTimeMillis()
        db.drugCacheDao().upsertAll(retry.map {
            DrugCacheEntity(it.rxcui, it.name, it.synonym, it.tty, now)
        })
        return retry.firstOrNull()?.rxcui
    }

    suspend fun refreshInteractions(context: Context): Int {
        val db = AegisDatabase.get(context)
        val meds = db.medicationDao().listActive()

        val online = runCatching { Settings.onlineLookupsEnabled(context) }.getOrDefault(true)
        if (!online) throw IllegalStateException("Online lookups are disabled in Settings")

        val idByMed = HashMap<Long, String>()
        for (med in meds) {
            val existing = med.rxcui
            if (!existing.isNullOrBlank()) {
                idByMed[med.id] = existing
            } else {
                resolveRxcui(context, med.name)?.let { resolved ->
                    idByMed[med.id] = resolved
                    db.medicationDao().upsert(med.copy(rxcui = resolved))
                }
            }
        }

        val ids = idByMed.values.distinct()
        if (ids.size < 2) return 0

        val pairs = RxNormService.interactions(ids).getOrElse { throw it }
        val now = System.currentTimeMillis()
        val entities = pairs.map { p ->
            RemoteInteractionEntity(
                rxcuiA = p.rxcuiA,
                nameA = p.nameA,
                rxcuiB = p.rxcuiB,
                nameB = p.nameB,
                severityKey = severityKey(p.severityRaw),
                severityRaw = p.severityRaw,
                description = p.description,
                source = p.source ?: "NIH RxNorm",
                fetchedAt = now
            )
        }
        db.remoteInteractionDao().clearFor(ids)
        db.remoteInteractionDao().insertAll(entities)
        return entities.size
    }

    suspend fun enrichLabel(context: Context, med: MedicationEntity): Enrichment? {
        val online = runCatching { Settings.onlineLookupsEnabled(context) }.getOrDefault(true)
        if (!online) return null

        val rxcui = med.rxcui ?: resolveRxcui(context, med.name)
        if (rxcui != null && rxcui != med.rxcui && med.id != 0L) {
            AegisDatabase.get(context).medicationDao().upsert(med.copy(rxcui = rxcui))
        }
        val label = OpenFdaService.labelFor(med.name).getOrNull()
        return Enrichment(
            rxcui = rxcui,
            indications = label?.indications,
            dosage = label?.dosageAndAdmin,
            warnings = label?.warnings,
            drugInteractions = label?.drugInteractions,
            brand = label?.brandName,
            generic = label?.genericName,
            doseForm = label?.dosageForm
        )
    }

    private val STRENGTH_RX =
        Regex("""(\d+(?:\.\d+)?)\s*(mcg|mg|g|ml|iu|unit)s?\b""", RegexOption.IGNORE_CASE)

    fun extractStrength(displayName: String): Pair<Double, String>? {
        val m = STRENGTH_RX.find(displayName) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        var unit = m.groupValues[2].lowercase()
        if (unit == "unit") unit = "iu"
        return value to unit
    }

    fun guessForm(displayName: String): String? {
        val lower = displayName.lowercase()
        return when {
            "capsule" in lower -> "capsule"
            "tablet" in lower || "tab " in lower -> "tablet"
            "solution" in lower || "syrup" in lower || "suspension" in lower || "elixir" in lower -> "liquid"
            "injection" in lower || "injectable" in lower -> "injection"
            "drops" in lower -> "drops"
            "patch" in lower -> "patch"
            else -> null
        }
    }
}
