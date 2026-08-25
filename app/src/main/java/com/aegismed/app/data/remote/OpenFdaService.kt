package com.aegismed.app.data.remote

import org.json.JSONObject

data class DrugLabelInfo(
    val brandName: String?,
    val genericName: String?,
    val dosageForm: String?,
    val indications: String?,
    val dosageAndAdmin: String?,
    val warnings: String?,
    val drugInteractions: String?
)

object OpenFdaService {

    private const val BASE = "https://api.fda.gov/drug/label.json"

    suspend fun labelFor(drugName: String): Result<DrugLabelInfo?> =
        runCatching {
            val cleaned = drugName.trim().substringBefore(" ").ifBlank { return@runCatching null }
            val token = "%22${java.net.URLEncoder.encode(cleaned, "UTF-8")}%22"
            val attempts = listOf(
                "openfda.brand_name:$token",
                "openfda.generic_name:$token",
                "openfda.substance_name:$token"
            )
            var lastCode = 0
            for (search in attempts) {
                val resp = Http.get("$BASE?search=$search&limit=1")
                lastCode = resp.code
                if (resp.code == 200) {
                    parseLabel(resp.body)?.let { return@runCatching it }
                } else if (resp.code == 429) {
                    break
                }
            }
            if (lastCode !in setOf(200, 404, 400)) {
                throw IllegalStateException("openFDA HTTP $lastCode")
            }
            null
        }

    private fun firstOrJoin(arr: org.json.JSONArray?, maxLen: Int = 900): String? {
        if (arr == null || arr.length() == 0) return null
        return arr.optString(0).takeIf { it.isNotBlank() }?.take(maxLen)
    }

    private fun parseLabel(body: String): DrugLabelInfo? {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        val openfda = r.optJSONObject("openfda") ?: JSONObject()

        fun str(key: String): String? {
            val arr = r.optJSONArray(key) ?: return null
            return firstOrJoin(arr)
        }

        return DrugLabelInfo(
            brandName = firstOrJoin(openfda.optJSONArray("brand_name"), 80),
            genericName = firstOrJoin(openfda.optJSONArray("generic_name"), 120),
            dosageForm = firstOrJoin(openfda.optJSONArray("dosage_form"), 60),
            indications = str("indications_and_usage"),
            dosageAndAdmin = str("dosage_and_administration"),
            warnings = str("warnings") ?: str("boxed_warning"),
            drugInteractions = str("drug_interactions")
        )
    }
}
