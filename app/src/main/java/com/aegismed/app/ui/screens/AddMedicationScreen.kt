package com.aegismed.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.aegismed.app.data.db.MedicationEntity
import com.aegismed.app.data.db.ScheduleRuleEntity
import com.aegismed.app.data.repo.ClinicalLookupRepository
import com.aegismed.app.domain.AnchorKind
import com.aegismed.app.domain.RuleType
import com.aegismed.app.domain.RoutineProfile
import com.aegismed.app.domain.Tier
import com.aegismed.app.domain.VerificationMode
import com.aegismed.app.ocr.ParsedPrescription
import com.aegismed.app.ocr.PrescriptionParser
import com.aegismed.app.ocr.ScannerActivity
import com.aegismed.app.ui.AppViewModel
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddMedicationScreen(vm: AppViewModel, nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("mg") }
    var form by remember { mutableStateOf("tablet") }
    var tierIdx by remember { mutableStateOf(Tier.STANDARD.ordinal) }
    var verModeIdx by remember { mutableStateOf(VerificationMode.TAP.ordinal) }
    var sigNotes by remember { mutableStateOf("") }

    var rxcui by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<com.aegismed.app.data.db.DrugCacheEntity>>(emptyList()) }
    var enrichment by remember { mutableStateOf<com.aegismed.app.data.repo.ClinicalLookupRepository.Enrichment?>(null) }
    var fetchingLabel by remember { mutableStateOf(false) }

    var ruleIdx by remember { mutableStateOf(RuleType.FIXED_TIMES.ordinal) }
    var times by remember { mutableStateOf(listOf("09:00")) }
    var intervalHours by remember { mutableStateOf("12") }
    var minInterval by remember { mutableStateOf("4") }
    var nightParity by remember { mutableStateOf(false) }
    var onDays by remember { mutableStateOf("21") }
    var offDays by remember { mutableStateOf("7") }
    var anchorKind by remember { mutableStateOf(AnchorKind.BREAKFAST) }
    var offsetMin by remember { mutableStateOf("30") }
    var routineProfile by remember { mutableStateOf(RoutineProfile.ANY) }

    var stockUnits by remember { mutableStateOf("30") }
    var unitsPerDose by remember { mutableStateOf("1") }
    var refillAt by remember { mutableStateOf("5") }
    var nameWasPicked by remember { mutableStateOf(false) }

    val applyPrefill: (ParsedPrescription) -> Unit = { p ->
        p.drugName?.let { name = it }
        p.strengthValue?.let { strength = PrescriptionParser.formatNum(it) }
        p.strengthUnit?.let { unit = it }
        p.form?.let { form = it }
        sigNotes = p.sigNotes
        if (p.intervalHours != null && p.intervalHours >= 1.0) {
            ruleIdx = RuleType.INTERVAL_HOURS.ordinal
            intervalHours = PrescriptionParser.formatNum(p.intervalHours)
        } else if (!p.suggestedTimes.isEmpty()) {
            ruleIdx = RuleType.FIXED_TIMES.ordinal
            times = p.suggestedTimes
        }
        if (p.quantity != null) stockUnits = PrescriptionParser.formatNum(p.quantity)
        if ((p.mealInstruction ?: "").contains("food", ignoreCase = true)) {
            tierIdx = Tier.STANDARD.ordinal
        }
    }

    val textScanner = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(ScannerActivity.EXTRA_RESULT)
        if (result.resultCode == android.app.Activity.RESULT_OK && !raw.isNullOrBlank()) {
            applyPrefill(PrescriptionParser.parse(raw))
        }
    }

    LaunchedEffect(name) {
        kotlinx.coroutines.delay(350)
        val q = name.trim()
        if (q.length >= 2 && !nameWasPicked) {
            suggestions = vm.searchDrugs(q)
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Add medication", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold)

        Button(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = {
                textScanner.launch(
                    Intent(context, ScannerActivity::class.java)
                        .putExtra(ScannerActivity.EXTRA_MODE, ScannerActivity.MODE_TEXT)
                )
            }
        ) {
            Icon(Icons.Filled.CameraAlt, null)
            Spacer(Modifier.width(8.dp))
            Text("Scan prescription label")
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameWasPicked = false
                        rxcui = null
                        enrichment = null
                    },
                    label = { Text("Drug name (RxNorm autocomplete)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (suggestions.isNotEmpty()) {
                    suggestions.take(5).forEach { s ->
                        val display = s.synonym ?: s.name
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    nameWasPicked = true
                                    name = display
                                    rxcui = s.rxcui
                                    ClinicalLookupRepository.extractStrength(display)?.let { (v, u) ->
                                        strength = PrescriptionParser.formatNum(v)
                                        unit = u
                                    }
                                    ClinicalLookupRepository.guessForm(display)?.let { form = it }
                                    suggestions = emptyList()
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(display, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "RxCUI ${s.rxcui} · ${s.tty}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = strength, onValueChange = { strength = it },
                        label = { Text("Strength") },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("mg", "mcg", "g", "ml", "iu").forEach { u ->
                            FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) })
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("tablet", "capsule", "liquid", "injection", "drops", "patch").forEach { f ->
                        FilterChip(selected = form == f, onClick = { form = f }, label = { Text(f) })
                    }
                }
                OutlinedTextField(
                    value = sigNotes, onValueChange = { sigNotes = it },
                    label = { Text("Sig / instructions") }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                OutlinedButton(
                    enabled = name.trim().length >= 2 && !fetchingLabel,
                    onClick = {
                        scope.launch {
                            fetchingLabel = true
                            enrichment = vm.enrichLabelForName(name.trim(), rxcui)
                            fetchingLabel = false
                        }
                    }
                ) {
                    Text(if (fetchingLabel) "Checking RxNorm / openFDA…" else "Fetch clinical details")
                }
                if (rxcui != null || enrichment != null) {
                    Text(
                        buildString {
                            rxcui?.let { append("RxCUI $it") }
                            enrichment?.let { e ->
                                if (rxcui != null) append(" · ")
                                listOfNotNull(e.brand, e.generic).joinToString(" / ")
                                    .takeIf { it.isNotBlank() }?.let { append(it) }
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                enrichment?.let { e ->
                    listOfNotNull(
                        e.indications?.let { "Indications: $it" },
                        e.dosage?.let { "Dosage: $it" },
                        e.warnings?.let { "Warnings: $it" }
                    ).forEach { sectionText ->
                        Text(
                            sectionText.take(600),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Clinical severity tier", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tier.entries.forEach { t ->
                        FilterChip(
                            selected = tierIdx == t.ordinal, onClick = { tierIdx = t.ordinal },
                            label = { Text(t.label) }
                        )
                    }
                }
                Text(
                    when (Tier.entries[tierIdx]) {
                        Tier.CRITICAL -> "Critical: full-screen alarms bypassing mute, caregiver escalation after 45 min."
                        Tier.STANDARD -> "Standard: high-priority reminders with snooze."
                        Tier.ELECTIVE -> "Elective: quiet daily digest, no interruptions."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Verification mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VerificationMode.entries.forEach { m ->
                        FilterChip(selected = verModeIdx == m.ordinal, onClick = { verModeIdx = m.ordinal },
                            label = { Text(m.label) })
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleType.entries.forEach { r ->
                        FilterChip(selected = ruleIdx == r.ordinal, onClick = { ruleIdx = r.ordinal },
                            label = { Text(r.label) })
                    }
                }

                val rt = RuleType.entries[ruleIdx]

                if (rt in setOf(RuleType.FIXED_TIMES, RuleType.ALTERNATING_DAYS, RuleType.CYCLE, RuleType.TAPER)) {
                    TimeChipsEditor(times) { times = it }
                }

                when (rt) {
                    RuleType.INTERVAL_HOURS -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = intervalHours, onValueChange = { intervalHours = it },
                            label = { Text("Every N hours") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = minInterval, onValueChange = { minInterval = it },
                            label = { Text("Safety floor (h)") }, modifier = Modifier.weight(1f), singleLine = true)
                    }

                    RuleType.ALTERNATING_DAYS -> Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = !nightParity, onClick = { nightParity = false }, label = { Text("Even days") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = nightParity, onClick = { nightParity = true }, label = { Text("Odd days") })
                    }

                    RuleType.CYCLE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = onDays, onValueChange = { onDays = it },
                            label = { Text("On days") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = offDays, onValueChange = { offDays = it },
                            label = { Text("Off days") }, modifier = Modifier.weight(1f), singleLine = true)
                    }

                    RuleType.TAPER -> Text(
                        "Taper uses fixed times above; edit steps in medication detail later.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    RuleType.RELATIVE_ANCHOR -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AnchorKind.entries.forEach { a ->
                                FilterChip(selected = anchorKind == a, onClick = { anchorKind = a },
                                    label = { Text(a.label) })
                            }
                        }
                        OutlinedTextField(value = offsetMin, onValueChange = { offsetMin = it },
                            label = { Text("Minutes after anchor") }, singleLine = true)
                    }

                    else -> {}
                }

                Text("Active under routine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoutineProfile.entries.forEach { p ->
                        FilterChip(selected = routineProfile == p,
                            onClick = { routineProfile = p }, label = { Text(p.label) })
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Inventory & refills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = stockUnits, onValueChange = { stockUnits = it },
                        label = { Text("Units on hand") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = unitsPerDose, onValueChange = { unitsPerDose = it },
                        label = { Text("Units per dose") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(value = refillAt, onValueChange = { refillAt = it },
                    label = { Text("Alert when doses below") }, singleLine = true)
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth().height(58.dp),
            enabled = name.isNotBlank(),
            onClick = {
                val med = MedicationEntity(
                    name = name.trim(),
                    strengthValue = strength.toDoubleOrNull(),
                    strengthUnit = unit,
                    form = form,
                    tierOrdinal = tierIdx,
                    verificationModeOrdinal = verModeIdx,
                    sigNotes = sigNotes.ifBlank { null },
                    rxcui = rxcui
                )
                val todayEpochDay = LocalDate.now().toEpochDay()
                val rt = RuleType.entries[ruleIdx]
                val rule = ScheduleRuleEntity(
                    medicationId = 0,
                    ruleTypeOrdinal = rt.ordinal,
                    timesJson = if (rt != RuleType.INTERVAL_HOURS && rt != RuleType.RELATIVE_ANCHOR)
                        toJsonArrayStrings(times.ifEmpty { listOf("09:00") }) else "",
                    intervalHours = if (rt == RuleType.INTERVAL_HOURS) intervalHours.toDoubleOrNull() ?: 12.0 else null,
                    minIntervalHours = minInterval.toDoubleOrNull() ?: 4.0,
                    anchorKindOrdinal = if (rt == RuleType.RELATIVE_ANCHOR) anchorKind.ordinal else null,
                    offsetMinutes = if (rt == RuleType.RELATIVE_ANCHOR) offsetMin.toIntOrNull() ?: 0 else 0,
                    startDayEpoch = todayEpochDay,
                    dayParity = if (rt == RuleType.ALTERNATING_DAYS) if (nightParity) 1 else 0 else null,
                    onDays = if (rt == RuleType.CYCLE) onDays.toIntOrNull() ?: 21 else null,
                    offDays = if (rt == RuleType.CYCLE) offDays.toIntOrNull() ?: 7 else null,
                    routineProfileOrdinal = routineProfile.ordinal
                )
                vm.saveMedicationWithRxcui(
                    med = med, rule = rule,
                    initialUnits = stockUnits.toDoubleOrNull(),
                    unitsPerDose = unitsPerDose.toDoubleOrNull() ?: 1.0,
                    threshold = refillAt.toDoubleOrNull() ?: 5.0
                )
                nav.popBackStack()
            }
        ) { Text("Save medication", fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun TimeChipsEditor(times: List<String>, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Times of day (HH:mm)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        times.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = t,
                    onValueChange = { v ->
                        onChange(times.toMutableList().also { it[i] = v })
                    },
                    label = { Text("Time ${i + 1}") },
                    singleLine = true,
                    modifier = Modifier.width(140.dp)
                )
                IconButton2(onClick = {
                    if (times.size > 1) onChange(times.filterIndexed { idx, _ -> idx != i })
                }) { Icon(Icons.Filled.Delete, "Remove") }
            }
        }
        OutlinedButton(onClick = { onChange(times + "12:00") }) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add time")
        }
    }
}

@Composable
private fun IconButton2(onClick: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) { content() }
}

internal fun toJsonArrayStrings(items: List<String>): String =
    org.json.JSONArray(items.mapNotNull { it.trim().ifBlank { null } }).toString()
