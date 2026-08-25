package com.aegismed.app.domain

import android.content.Context
import com.aegismed.app.data.db.MedicationEntity
import org.json.JSONArray
import org.json.JSONObject

data class InteractionRuleDef(
    val id: String,
    val title: String,
    val severity: String,
    val description: String,
    val mechanism: String?,
    val category: String?,
    val aTokens: List<String>,
    val bTokens: List<String>
)

data class InteractionResult(
    val ruleId: String,
    val severity: String,
    val title: String,
    val description: String,
    val mechanism: String?,
    val involvesMedIds: List<Long>,
    val counterpartLabel: String,
    val source: String = "Bundled rules"
)

class InteractionEngine private constructor(private val rules: List<InteractionRuleDef>) {

    fun evaluate(activeMeds: List<MedicationEntity>): List<InteractionResult> {
        if (activeMeds.isEmpty()) return emptyList()
        val results = mutableListOf<InteractionResult>()
        for (rule in rules) {
            val aMatches = activeMeds.filter { med -> rule.aTokens.any { matches(med.name, it) } }
            if (aMatches.isEmpty()) continue

            val bMedMatches = activeMeds.filter { med ->
                !aMatches.contains(med) && rule.bTokens.any { matches(med.name, it) }
            }

            if (rule.category != null || bMedMatches.isNotEmpty()) {
                val involved = aMatches.map { it.id }
                val label = when {
                    bMedMatches.isNotEmpty() -> bMedMatches.joinToString { it.name }
                    rule.categoryLabel() != null -> rule.categoryLabel()!!
                    else -> continue
                }
                results += InteractionResult(
                    ruleId = rule.id,
                    severity = rule.severity,
                    title = rule.title,
                    description = rule.description,
                    mechanism = rule.mechanism,
                    involvesMedIds = involved,
                    counterpartLabel = label
                )
            } else if (rule.bTokens.isEmpty() && rule.category == null) {
                results += InteractionResult(
                    ruleId = rule.id,
                    severity = rule.severity,
                    title = rule.title,
                    description = rule.description,
                    mechanism = rule.mechanism,
                    involvesMedIds = aMatches.map { it.id },
                    counterpartLabel = ""
                )
            }
        }
        return results.distinctBy { it.ruleId to it.counterpartLabel }
    }

    fun evaluateWithRemote(
        activeMeds: List<MedicationEntity>,
        remoteRows: List<com.aegismed.app.data.db.RemoteInteractionEntity>
    ): List<InteractionResult> {
        val local = evaluate(activeMeds)
        if (remoteRows.isEmpty()) return local

        val rxcuiToMed = activeMeds.mapNotNull { m -> m.rxcui?.let { it to m } }.toMap()
        val remoteResults = mutableListOf<InteractionResult>()
        for (row in remoteRows) {
            val medA = rxcuiToMed[row.rxcuiA]
            val medB = rxcuiToMed[row.rxcuiB]
            if (medA == null && medB == null) continue
            if (medA != null && medB != null && !activeMeds.containsAll(listOf(medA, medB))) continue

            val involvedIds = listOfNotNull(medA?.id, medB?.id)
            val label = when {
                medA != null && medB != null -> "${medA.name} + ${medB.name}"
                medA != null -> row.nameB
                else -> row.nameA
            }
            remoteResults += InteractionResult(
                ruleId = "rxnav_${row.id}",
                severity = row.severityKey,
                title = "${row.nameA} + ${row.nameB}",
                description = row.description.ifBlank { "Interaction reported by NIH RxNorm (DrugBank/ONC high-alert sources)." },
                mechanism = row.source,
                involvesMedIds = involvedIds,
                counterpartLabel = label,
                source = "NIH RxNorm"
            )
        }
        return (local + remoteResults).distinctBy { it.title.lowercase() + it.severity }
    }

    private fun matches(name: String, token: String): Boolean =
        name.lowercase().replace(Regex("[^a-z0-9 ]"), " ").contains(token)

    private fun InteractionRuleDef.categoryLabel(): String? = when (category) {
        "food" -> "dietary trigger"
        "herb" -> "herbal supplement"
        "otc" -> "OTC product"
        "alcohol" -> "alcohol"
        else -> null
    }

    companion object {
        @Volatile private var instance: InteractionEngine? = null

        fun get(context: Context): InteractionEngine =
            instance ?: synchronized(this) {
                instance ?: InteractionEngine(loadRules(context)).also { instance = it }
            }

        private fun loadRules(context: Context): List<InteractionRuleDef> =
            runCatching {
                val raw = context.assets.open("interactions.json").bufferedReader().use { it.readText() }
                val arr: JSONArray = JSONObject(raw).getJSONArray("rules")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    InteractionRuleDef(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        severity = o.getString("severity"),
                        description = o.getString("description"),
                        mechanism = o.optString("mechanism", "").ifBlank { null },
                        category = o.optString("category", "").ifBlank { null },
                        aTokens = stringList(o, "aTokens"),
                        bTokens = stringList(o, "bTokens")
                    )
                }
            }.getOrDefault(emptyList())

        private fun stringList(o: JSONObject, key: String): List<String> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { arr.getString(it).lowercase() }
        }
    }
}
