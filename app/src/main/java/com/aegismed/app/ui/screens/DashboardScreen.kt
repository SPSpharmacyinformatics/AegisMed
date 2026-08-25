package com.aegismed.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegismed.app.domain.AnchorKind
import com.aegismed.app.domain.DoseStatus
import com.aegismed.app.domain.ScheduleEngine
import com.aegismed.app.domain.Tier
import com.aegismed.app.ui.AppViewModel
import com.aegismed.app.ui.Routes
import com.aegismed.app.ui.theme.AnchorBlue
import com.aegismed.app.ui.theme.OkGreen
import com.aegismed.app.ui.theme.TierCriticalColor
import com.aegismed.app.ui.theme.TierElectiveColor
import com.aegismed.app.ui.theme.TierStandardColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: AppViewModel, nav: androidx.navigation.NavHostController) {
    val state by vm.dashboard.collectAsState()
    var selectedSlot by remember { mutableStateOf<ScheduleEngine.DoseSlot?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AegisMed", fontWeight = FontWeight.Bold) },
                actions = {
                    if (state.interactions.isNotEmpty()) {
                        IconButton(onClick = { nav.navigate(Routes.INTERACTIONS) }) {
                            Icon(Icons.Filled.WarningAmber, "Interaction warnings",
                                tint = TierCriticalColor)
                        }
                    }
                    IconButton(onClick = { nav.navigate(Routes.REPORTS) }) {
                        Icon(Icons.Filled.BarChart, "Reports")
                    }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { nav.navigate(Routes.ADD) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) { Icon(Icons.Filled.Add, "Add medication") }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item { AnchorQuickMarks(vm, state.anchorsMarkedToday) }

            if (!state.loading && state.actionNeeded.isEmpty() && state.upcoming.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("All clear", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No medications yet.\nTap + to add one, or scan a prescription label for 1-tap setup.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            androidx.compose.material3.Button(onClick = { nav.navigate(Routes.ADD) }) {
                                Text("Scan prescription")
                            }
                            Spacer(Modifier.height(48.dp))
                        }
                    }
                }
            }

            if (state.interactions.any { it.severity == "contraindicated" || it.severity == "major" }) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = TierCriticalColor.copy(alpha = 0.12f)),
                        modifier = Modifier.fillMaxWidth().clickable { nav.navigate(Routes.INTERACTIONS) }
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.WarningAmber, null, tint = TierCriticalColor)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Major interaction risk detected — review now",
                                color = TierCriticalColor, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (state.actionNeeded.isNotEmpty()) {
                item {
                    SectionHeader("ACTION NEEDED", TierCriticalColor)
                }
                items(state.actionNeeded, key = { "${it.medicationId}-${it.scheduledFor}" }) { slot ->
                    SlotCard(slot, onClick = { selectedSlot = slot })
                }
            }

            if (state.upcoming.isNotEmpty()) {
                item { SectionHeader("UPCOMING", MaterialTheme.colorScheme.primary) }
                items(state.upcoming.take(6), key = { "u-${it.medicationId}-${it.scheduledFor}" }) { slot ->
                    SlotCard(slot, onClick = { selectedSlot = slot })
                }
            }

            item { SectionHeader("DAILY OVERVIEW", MaterialTheme.colorScheme.secondary) }
            item {
                OverviewCard(
                    completedToday = state.completedToday,
                    totalToday = state.totalToday,
                    adherencePct = state.adherencePct30d
                )
            }

            val lowStocks = state.stocks.filter { it.isLow || (it.daysLeftEstimate ?: 99.0) < 3.0 }
            if (lowStocks.isNotEmpty()) {
                item { SectionHeader("REFILL SOON", TierStandardColor) }
                items(lowStocks, key = { "s-${it.inventory.medicationId}" }) { stock ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${stock.medication.name}", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${stock.dosesRemaining} doses left" +
                                    (stock.daysLeftEstimate?.let { " · ~${kotlin.math.ceil(it).toInt()} days" } ?: ""),
                                color = TierStandardColor, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    selectedSlot?.let { slot ->
        ModalBottomSheet(onDismissRequest = { selectedSlot = null }) {
            LogDoseSheet(vm, slot, onDone = { selectedSlot = null })
        }
    }
}

@Composable
fun SectionHeader(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = color,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
fun SlotCard(slot: ScheduleEngine.DoseSlot, onClick: () -> Unit) {
    val accent = when (slot.tier) {
        Tier.CRITICAL -> TierCriticalColor
        Tier.STANDARD -> TierStandardColor
        Tier.ELECTIVE -> TierElectiveColor
    }
    val statusLabel = when (slot.status) {
        DoseStatus.DUE -> "Due now"
        DoseStatus.LATE -> "Overdue"
        DoseStatus.MISSED -> "Missed"
        DoseStatus.TAKEN -> "Taken"
        DoseStatus.SKIPPED -> "Skipped"
        DoseStatus.UPCOMING -> ""
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (slot.status in setOf(DoseStatus.DUE, DoseStatus.LATE, DoseStatus.MISSED))
                accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(accent, RoundedCornerShape(5.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${slot.medicationName} ${if (slot.strengthLabel.isNotBlank()) slot.strengthLabel else ""}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val amountText = if (slot.amount != 1.0)
                    "${ScheduleEngine.prettyAmount(slot.amount)} ${slot.unitLabel}"
                else slot.unitLabel
                Text(
                    buildString {
                        append("${timeLabel(slot)} · ")
                        append(amountText)
                        if (slot.driftAdjusted) append(" · shifted for safety")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (slot.tier == Tier.CRITICAL) {
                    Text(
                        "CRITICAL MEDICATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = TierCriticalColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (statusLabel.isNotEmpty()) {
                Text(
                    statusLabel,
                    color = accent,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

private fun timeLabel(slot: ScheduleEngine.DoseSlot): String =
    Instant.ofEpochMilli(slot.scheduledFor).atZone(ZoneId.systemDefault()).format(timeFmt)

@Composable
private fun AnchorQuickMarks(vm: AppViewModel, marked: Set<AnchorKind>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Daily anchors", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AnchorKind.WAKE, AnchorKind.BREAKFAST, AnchorKind.BEDTIME).forEach { kind ->
                    val isMarked = kind in marked
                    androidx.compose.material3.FilterChip(
                        selected = isMarked,
                        onClick = { vm.markAnchor(kind) },
                        label = { Text(kind.label) },
                        leadingIcon = if (isMarked) {
                            { Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = OkGreen) }
                        } else null
                    )
                }
            }
            Text(
                "Relative schedules fire after these marks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OverviewCard(completedToday: Int, totalToday: Int, adherencePct: Int) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(Color.LightGray.copy(alpha = 0.35f), -90f, 360f, false, style = stroke)
                    drawArc(OkGreen, -90f, 360f * adherencePct / 100f, false, style = stroke)
                }
                Text("$adherencePct%", fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text("30-day adherence", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "$completedToday of $totalToday doses taken today",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
