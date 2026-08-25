package com.aegismed.app.ocr

import java.util.Locale

data class ParsedPrescription(
    val drugName: String? = null,
    val strengthValue: Double? = null,
    val strengthUnit: String? = null,
    val form: String? = null,
    val frequencyTimesPerDay: Int? = null,
    val intervalHours: Double? = null,
    val suggestedTimes: List<String> = emptyList(),
    val mealInstruction: String? = null,
    val quantity: Double? = null,
    val sigNotes: String
)

object PrescriptionParser {

    private val STRENGTH_RX = Regex("""(\d+(?:[.,]\d+)?)\s*(mcg|mg|g|ml|iu|unit)s?\b""", RegexOption.IGNORE_CASE)
    private val EVERY_HOURS_RX = Regex("""every\s+(\d+(?:\.\d+)?)\s*(?:hours?|hrs?|h)\b""", RegexOption.IGNORE_CASE)
    private val QTY_RX = Regex("""(?:qty|quantity|dispense|#)\s*:?\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
    private val QTY_TABLETS_RX = Regex("""\b(\d{1,3})\s*(?:tablets?|tab|capsules?|caps)\b""", RegexOption.IGNORE_CASE)

    private val NOISE_WORDS = listOf(
        "rx", "prescription", "pharmacy", "dr ", "doctor", "patient", "address",
        "refill", "sig:", "disp", "take", "daily", "warning", "store", "lot",
        "expir", "ndc", "physician", "clinic", "hospital"
    )

    fun parse(rawText: String): ParsedPrescription {
        val lines = rawText.lines().map { it.trim() }.filter { it.length >= 3 }
        var bestName: String? = null
        var bestScore = -1

        for (line in lines.take(12)) {
            val cleaned = line.trim(':', '-', '*', '•', ' ')
            if (cleaned.length !in 4..40) continue
            if (!cleaned.any { it.isLetter() }) continue
            if (cleaned.count { it.isDigit() } > cleaned.length / 2) continue

            var score = 0
            val upperRatio = cleaned.count { it.isUpperCase() } /
                maxOf(1.0, cleaned.count { it.isLetter() }.toDouble())
            if (upperRatio > 0.6) score += 3
            if (cleaned.split(Regex("\\s+")).size in 1..3) score += 2
            val lower = cleaned.lowercase(Locale.ROOT)
            if (STRENGTH_RX.containsMatchIn(cleaned)) score += 1
            if (NOISE_WORDS.any { lower.startsWith(it) }) score -= 4
            if (lower.contains("tablet") || lower.contains("capsule")) score += 1
            if (score > bestScore) {
                bestScore = score
                bestName = cleaned.substringBefore(",").trim()
                    .replace(Regex("""\b\d+(\.\d+)?\s*(mg|mcg|g|ml|iu)\b.*""", RegexOption.IGNORE_CASE), "")
                    .trim()
            }
            if (bestName.isNullOrBlank()) bestName = cleaned
        }

        val strengthMatch = STRENGTH_RX.find(rawText)
        val strengthValue = strengthMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val strengthUnitRaw = strengthMatch?.groupValues?.get(2)?.lowercase(Locale.ROOT)
        val strengthUnit = when (strengthUnitRaw) {
            "unit" -> "iu"
            else -> strengthUnitRaw
        }

        val lowerAll = rawText.lowercase(Locale.ROOT)

        val form = when {
            lowerAll.contains("capsule") -> "capsule"
            lowerAll.contains("syrup") || lowerAll.contains("oral solution") -> "liquid"
            lowerAll.contains("drops") -> "drops"
            lowerAll.contains("injection") || lowerAll.contains("injectable") -> "injection"
            lowerAll.contains("patch") -> "patch"
            else -> "tablet"
        }

        val intervalHours = EVERY_HOURS_RX.find(rawText)?.groupValues?.get(1)?.toDoubleOrNull()

        val frequencyTimesPerDay = when {
            Regex("""three\s+times|tds|tid|3\s*x\s*a?\s*day""").containsMatchIn(lowerAll) -> 3
            Regex("""twice|bid|bis\s+die|2\s*x\s*a?\s*day|two\s+times""").containsMatchIn(lowerAll) -> 2
            Regex("""once\s+daily|od\b|qd\b|one\s+a?\s*day|everyday|every\s+night|every\s+morning""")
                .containsMatchIn(lowerAll) -> 1
            intervalHours != null && intervalHours >= 1 ->
                ((24.0 / intervalHours).toInt()).coerceIn(1, 8)
            else -> null
        }

        val suggestedTimes = when {
            intervalHours != null -> defaultSpread(frequencyTimesPerDay ?: (24.0 / intervalHours).toInt())
            frequencyTimesPerDay == 1 ->
                listOf(if (lowerAll.contains("bedtime") || lowerAll.contains("night")) "22:00" else "08:00")
            frequencyTimesPerDay == 2 -> listOf("09:00", "21:00")
            frequencyTimesPerDay == 3 -> listOf("08:00", "14:00", "20:00")
            frequencyTimesPerDay == 4 -> listOf("08:00", "12:00", "16:00", "20:00")
            else -> emptyList()
        }

        val mealInstruction = when {
            lowerAll.contains("empty stomach") -> "Take on an empty stomach"
            lowerAll.contains("before food") || lowerAll.contains("before meals") -> "Take before food"
            lowerAll.contains("with food") || lowerAll.contains("after meals") ||
                lowerAll.contains("after food") -> "Take with food"
            else -> null
        }

        val quantity = QTY_RX.find(rawText)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: QTY_TABLETS_RX.find(rawText)?.groupValues?.get(1)?.toDoubleOrNull()

        val sigNotes = buildString {
            append(bestName?.let { "$it" } ?: "")
            if (strengthValue != null) append(" ${formatNum(strengthValue)}${strengthUnit ?: ""}")
            append(" — ")
            append(
                when (frequencyTimesPerDay) {
                    1 -> "Once daily"
                    2 -> "Twice daily"
                    3 -> "Three times daily"
                    else -> intervalHours?.let { "Every ${formatNum(it)} hours" } ?: "As directed"
                }
            )
            if (mealInstruction != null) append(", $mealInstruction")
        }.replaceFirstChar { it.uppercase(Locale.ROOT) }

        return ParsedPrescription(
            drugName = bestName?.ifBlank { null },
            strengthValue = strengthValue,
            strengthUnit = strengthUnit,
            form = form,
            frequencyTimesPerDay = frequencyTimesPerDay,
            intervalHours = intervalHours,
            suggestedTimes = suggestedTimes,
            mealInstruction = mealInstruction,
            quantity = quantity,
            sigNotes = sigNotes
        )
    }

    fun formatNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v).removeSuffix(".0")

    private fun defaultSpread(times: Int): List<String> {
        val n = times.coerceIn(1, 8)
        val startHour = 8
        val step = (18.0 / maxOf(n, 1)).toInt().coerceAtLeast(1)
        return (0 until n).map { i ->
            val h = (startHour + i * step).mod(24)
            "%02d:%02d".format(h, 0)
        }
    }
}
