package com.aegismed.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegismed.app.data.db.InventoryEntity
import com.aegismed.app.data.db.MedicationEntity
import com.aegismed.app.data.db.ScheduleRuleEntity
import com.aegismed.app.domain.RuleType
import com.aegismed.app.domain.ScheduleEngine
import com.aegismed.app.domain.Tier
import com.aegismed.app.domain.VerificationMode
import com.aegismed.app.ocr.ScannerActivity
import com.aegismed.app.ui.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedDetailScreen(
    vm: AppViewModel,
    nav: androidx.navigation.NavHostController,
    medId: Long
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var med by remember { mutableStateOf<MedicationEntity?>(null) }
    var rule by remember { mutableStateOf<ScheduleRuleEntity?>(null) }
    var inv by remember { mutableStateOf<InventoryEntity?>(null) }
    var refillAmount by remember { mutableStateOf("30") }
    var labelInfo by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(medId) {
        med = vm.medById(medId)
        rule = vm.ruleFor(medId)
        inv = vm.inventoryFor(medId)
        vm.refresh()
    }

    val barcodeScanner = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = result.data?.getStringExtra(ScannerActivity.EXTRA_RESULT)
        if (result.resultCode == android.app.Activity.RESULT_OK && !code.isNullOrBlank()) {
            scope.launch {
                med?.let { m ->
                    vm.saveMedication(m.copy(barcode = code.trim()),
                        rule ?: ScheduleRuleEntity(medicationId = m.id, ruleTypeOrdinal = RuleType.FIXED_TIMES.ordinal),
                        null, inv?.unitsPerDose ?: 1.0, inv?.refillThreshold ?: 5.0)
                    med = vm.medById(medId)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(med?.name ?: "Medication", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val m = med ?: return@Column

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tierName = when (Tier.entries[m.tierOrdinal]) {
                        Tier.CRITICAL -> "Critical"
                        Tier.STANDARD -> "Standard"
                        Tier.ELECTIVE -> "Elective"
                    }
                    Text("${m.name} ${ScheduleEngine.strengthLabel(m)}",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Tier: $tierName · ${VerificationMode.entries[m.verificationModeOrdinal].label}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    m.sigNotes?.let { Text(it) }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Schedule rules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val r = rule
                    if (r == null) {
                        Text("No active rule", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(RuleType.entries[r.ruleTypeOrdinal].label, fontWeight = FontWeight.SemiBold)
                        if (r.timesJson.isNotBlank()) {
                            Text("Times: ${r.timesJson.replace("[\"", "").replace("\"]", "").split("\",\"").joinToString()}")
                        }
                        if (r.ruleType == RuleType.INTERVAL_HOURS) {
                            Text("Every ${r.intervalHours}h · safety floor ${r.minIntervalHours}h")
                        }
                        if (r.ruleType == RuleType.RELATIVE_ANCHOR) {
                            Text("${r.anchorKind?.label} + ${r.offsetMinutes} min")
                        }
                        if (r.ruleType == RuleType.ALTERNATING_DAYS) {
                            Text("Active on ${if ((r.dayParity ?: 0) == 0) "even" else "odd"} days")
                        }
                        if (r.ruleType == RuleType.CYCLE) {
                            Text("${r.onDays} days on / ${r.offDays} days off")
                        }
                        if (!r.taperStepsJson.isNullOrBlank()) {
                            Text("Taper: ${ScheduleEngine.parseTaperSteps(r.taperStepsJson).joinToString { "${it.weekOffset}wk→${it.dose}" }}")
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hardware verification", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "NFC tag: ${m.nfcTagIdHex ?: "not bound"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.bindNfcTag(medId) }) {
                        Icon(Icons.Filled.Nfc, null); Spacer(Modifier.width(8.dp)); Text("Bind NFC bottle tag")
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        barcodeScanner.launch(
                            Intent(context, ScannerActivity::class.java)
                                .putExtra(ScannerActivity.EXTRA_MODE, ScannerActivity.MODE_BARCODE)
                        )
                    }) {
                        Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp))
                        Text(if (m.barcode.isNullOrBlank()) "Bind barcode" else "Re-bind barcode")
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inventory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        inv?.let {
                            "${ScheduleEngine.prettyAmount(it.unitsOnHand)} units on hand · alert below ${
                                ScheduleEngine.prettyAmount(it.refillThreshold)
                            }"
                        } ?: "No stock tracked",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = refillAmount, onValueChange = { refillAmount = it },
                            label = { Text("Refill units") }, singleLine = true, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            refillAmount.toDoubleOrNull()?.let { amt ->
                                scope.launch {
                                    MedRepositoryBridge.refill(vm, medId, amt)
                                    inv = vm.inventoryFor(medId)
                                }
                            }
                        }) { Text("Add") }
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Clinical data (NIH)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "RxNorm ID: ${m.rxcui ?: "not resolved yet"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            vm.refreshRemoteInteractions { }
                            med = vm.medById(medId)
                        }
                    }) {
                        Text("Sync RxNorm interactions")
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            val e = vm.enrichLabel(medId)
                            med = vm.medById(medId)
                            labelInfo = e?.let { listOfNotNull(
                                it.indications?.let { s -> "Indications: $s" },
                                it.dosage?.let { s -> "Dosage: $s" },
                                it.warnings?.let { s -> "Warnings: $s" }
                            ) }
                        }
                    }) {
                        Text("Fetch FDA label info")
                    }
                    (labelInfo ?: emptyList()).forEach {
                        Text(it.take(500), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (m.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                onClick = {
                    vm.setMedActive(medId, !m.active)
                    scope.launch { med = vm.medById(medId) }
                }
            ) {
                Text(if (m.active) "Pause medication" else "Resume medication", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

object MedRepositoryBridge {
    suspend fun refill(vm: AppViewModel, medId: Long, amount: Double) = vm.refillSuspend(medId, amount)
}
