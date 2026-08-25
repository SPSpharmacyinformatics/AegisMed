package com.aegismed.app.data.remote

import org.json.JSONObject

data class RxSuggestion(
    val rxcui: String,
    val name: String,
    val synonym: String?,
    val tty: String
)

data class RemoteInteractionPair(
    val rxcuiA: String,
    val nameA: String,
    val rxcuiB: String,
    val nameB: String,
    val severityRaw: String,
    val description: String,
    val source: String?
)

object RxNormService {

    private const val BASE = "https://rxnav.nlm.nih.gov/REST"

    private val TTY_PRIORITY = listOf("SBD", "SCD", "SBDG", "SCDG", "GPCK", "BPCK", "PIN", "IN", "MIN")

    suspend fun searchDrugs(query: String): Result<List<RxSuggestion>> =
        runCatching {
            val resp = Http.get("$BASE/drugs.json?name=${Http.enc(query)}")
            require(resp.code == 200) { "HTTP ${resp.code}" }
            parseDrugGroup(resp.body)
        }

    private fun parseDrugGroup(body: String): List<RxSuggestion> {
        val root = JSONObject(body)
        val group = root.optJSONObject("drugGroup") ?: return emptyList()
        val groups = group.optJSONArray("conceptGroup") ?: return emptyList()
        val out = mutableListOf<RxSuggestion>()
        for (i in 0 until groups.length()) {
            val props = groups.getJSONObject(i).optJSONArray("conceptProperties") ?: continue
            for (j in 0 until props.length()) {
                val p = props.getJSONObject(j)
                val rxcui = p.optString("rxcui").orEmpty()
                if (rxcui.isBlank()) continue
                out += RxSuggestion(
                    rxcui = rxcui,
                    name = p.optString("name"),
                    synonym = p.optString("synonym", "").ifBlank { null },
                    tty = p.optString("tty", "IN")
                )
            }
        }
        return out.sortedBy { TTY_PRIORITY.indexOf(it.tty).let { idx -> if (idx < 0) 99 else idx } }
            .distinctBy { it.rxcui }
            .take(12)
    }

    suspend fun spellSuggestions(query: String): List<String> =
        runCatching {
            val resp = Http.get("$BASE/spellingsuggestions.json?name=${Http.enc(query)}")
            if (resp.code != 200) return@runCatching emptyList<String>()
            val arr = JSONObject(resp.body)
                .optJSONObject("suggestionList")?.optJSONArray("suggestion")
                ?: return@runCatching emptyList<String>()
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())

    suspend fun interactions(rxcuis: List<String>): Result<List<RemoteInteractionPair>> =
        runCatching {
            require(rxcuis.size >= 2) { "Need at least two RxCUIs" }
            val joined = rxcuis.joinToString("+") { Http.enc(it) }
            val resp = Http.get("$BASE/interaction/list.json?rxcui=$joined&sources=DrugBank,ONCHighAlert")
            require(resp.code == 200) { "HTTP ${resp.code}" }
            parseInteractions(resp.body)
        }

    private fun parseInteractions(body: String): List<RemoteInteractionPair> {
        val root = JSONObject(body)
        val typeGroups = root.optJSONArray("fullInteractionTypeGroup") ?: return emptyList()
        val pairs = mutableListOf<RemoteInteractionPair>()
        for (g in 0 until typeGroups.length()) {
            val types = typeGroups.getJSONObject(g).optJSONArray("fullInteractionType") ?: continue
            for (t in 0 until types.length()) {
                val typeObj = types.getJSONObject(t)
                val pairArr = typeObj.optJSONArray("interactionPair") ?: continue
                for (p in 0 until pairArr.length()) {
                    val pair = pairArr.getJSONObject(p)
                    val concepts = pair.optJSONArray("interactionConcept") ?: continue
                    if (concepts.length() < 2) continue
                    val cA = concepts.getJSONObject(0).optJSONObject("minConcept")
                    val cB = concepts.getJSONObject(1).optJSONObject("minConcept")
                    if (cA == null || cB == null) continue
                    pairs += RemoteInteractionPair(
                        rxcuiA = cA.optString("rxcui"),
                        nameA = cA.optString("name"),
                        rxcuiB = cB.optString("rxcui"),
                        nameB = cB.optString("name"),
                        severityRaw = pair.optString("severity", "unknown"),
                        description = pair.optString("description", ""),
                        source = typeObj.optString("sourceName", "").ifBlank { null }
                            ?: pair.optString("source", "").ifBlank { null }
                    )
                }
            }
        }
        return pairs.distinctBy { listOf(it.rxcuiA, it.rxcuiB, it.description) }
    }
}
