package com.aegismed.app.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegismed.app.domain.ScheduleEngine
import com.aegismed.app.domain.Tier
import com.aegismed.app.domain.VerificationMode
import com.aegismed.app.ocr.ScannerActivity
import com.aegismed.app.ui.AppViewModel
import com.aegismed.app.ui.theme.OkGreen
import com.aegismed.app.ui.theme.TierCriticalColor
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogDoseSheet(vm: AppViewModel, slot: ScheduleEngine.DoseSlot, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var barcodeVerified by remember { mutableStateOf(false) }
    val nfcVerifiedMed by vm.nfcVerifiedMed.collectAsState()

    val needBarcode = slot.verificationMode == VerificationMode.BARCODE

    LaunchedEffect(nfcVerifiedMed) {
        if (nfcVerifiedMed == slot.medicationId) {
            vm.clearNfcVerified()
            vm.logDose(slot, taken = true)
            onDone()
        }
    }

    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        val time = Instant.ofEpochMilli(slot.scheduledFor)
            .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        Text(
            "${slot.medicationName} ${slot.strengthLabel}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            "Scheduled $time · ${
                if (slot.amount != 1.0) ScheduleEngine.prettyAmount(slot.amount) + " " else ""
            }${slot.unitLabel}",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (slot.driftAdjusted) {
            Text("Next-dose safety shift applied", color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(20.dp))

        when (slot.verificationMode) {
            VerificationMode.TAP -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.TouchApp, null)
                Spacer(Modifier.width(8.dp))
                Text("Simple tap verification")
            }

            VerificationMode.BARCODE -> {
                val scanner = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val code = result.data?.getStringExtra(ScannerActivity.EXTRA_RESULT)
                    if (result.resultCode == Activity.RESULT_OK && code != null) {
                        scope.launch {
                            barcodeVerified = vm.verifyBarcodeForMed(slot.medicationId, code)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.QrCodeScanner, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan the bottle barcode to confirm")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    scanner.launch(
                        Intent(context, ScannerActivity::class.java)
                            .putExtra(ScannerActivity.EXTRA_MODE, ScannerActivity.MODE_BARCODE)
                    )
                }) {
                    Text(if (barcodeVerified) "Barcode verified ✓" else "Scan barcode")
                }
            }

            VerificationMode.NFC -> {
                LaunchedEffect(slot.medicationId) {
                    vm.requestNfcVerification(slot.medicationId)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Nfc, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tap phone against the NFC tag on the bottle")
                }
                Text("Waiting for tag…", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary)
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            enabled = !needBarcode || barcodeVerified,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (slot.tier == Tier.CRITICAL) TierCriticalColor
                else MaterialTheme.colorScheme.primary
            ),
            onClick = {
                vm.logDose(slot, taken = true)
                onDone()
            }
        ) {
            Text("TAKE NOW", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                vm.logDose(slot, taken = false)
                onDone()
            }
        ) { Text("Skip this dose") }

        if (slot.tier == Tier.CRITICAL) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Critical medication — caregivers are alerted after 45 minutes without confirmation.",
                style = MaterialTheme.typography.bodySmall,
                color = TierCriticalColor
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
