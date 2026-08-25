package com.aegismed.app.domain

enum class Tier(val label: String, val escalationMinutes: Long, val retryMinutes: Long) {
    CRITICAL("Critical", 45L, 10L),
    STANDARD("Standard", 0L, 30L),
    ELECTIVE("Elective", 0L, 0L)
}

enum class VerificationMode(val label: String) {
    TAP("Simple tap"),
    NFC("NFC bottle tag"),
    BARCODE("Barcode scan")
}

enum class RuleType(val label: String) {
    FIXED_TIMES("Fixed clock times"),
    INTERVAL_HOURS("Every N hours"),
    ALTERNATING_DAYS("Alternating days"),
    CYCLE("Cycle on/off days"),
    TAPER("Tapering dose"),
    RELATIVE_ANCHOR("After an anchor event")
}

enum class AnchorKind(val label: String, val dayFallbackHour: Int, val nightFallbackHour: Int) {
    WAKE("Waking up", 7, 17),
    BREAKFAST("Breakfast", 8, 18),
    LUNCH("Lunch", 12, 23),
    DINNER("Dinner", 19, 9),
    BEDTIME("Bedtime", 22, 9)
}

enum class RoutineProfile(val label: String) {
    ANY("Any routine"),
    DAY("Day shift"),
    NIGHT("Night shift")
}

enum class DoseStatus { UPCOMING, DUE, LATE, TAKEN, SKIPPED, MISSED }

enum class ContactChannel { SMS, RELAY }

data class InteractionSeverityDef(val key: String, val label: String)

object SeverityLevels {
    val ALL = listOf(
        InteractionSeverityDef("contraindicated", "Contraindicated"),
        InteractionSeverityDef("major", "Major"),
        InteractionSeverityDef("moderate", "Moderate"),
        InteractionSeverityDef("minor", "Minor")
    )
}
