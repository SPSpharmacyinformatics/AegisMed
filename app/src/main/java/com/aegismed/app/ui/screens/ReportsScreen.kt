package com.aegismed.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegismed.app.ui.AppViewModel
import com.aegismed.app.ui.theme.OkGreen
import com.aegismed.app.ui.theme.TierCriticalColor
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(vm: AppViewModel, nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    var series by remember { mutableStateOf<List<Triple<LocalDate, Int, Int>>>(emptyList()) }

    LaunchedEffect(Unit) {
        series = com.aegismed.app.util.Exporters.buildAdherenceSeries(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health report", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val taken = series.sumOf { it.second }
            val missed = series.sumOf { it.third }
            val pct = if (taken + missed > 0) taken * 100 / (taken + missed) else 100

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Last 30 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("$pct% adherence", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold, color = OkGreen)
                    Text("$taken doses taken · $missed missed or skipped",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Dose consistency", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                        if (series.isNotEmpty()) {
                            val slotW = size.width / series.size
                            val maxVal = maxOf(1, series.maxOf { it.second + it.third })
                            series.forEachIndexed { i, (_, t, m) ->
                                val x = i * slotW
                                val hT = size.height * t / maxVal
                                val hM = size.height * m / maxVal
                                drawRect(OkGreen, topLeft = androidx.compose.ui.geometry.Offset(x, size.height - hT),
                                    size = androidx.compose.ui.geometry.Size(slotW * 0.65f, hT))
                                drawRect(TierCriticalColor,
                                    topLeft = androidx.compose.ui.geometry.Offset(x, size.height - hT - hM),
                                    size = androidx.compose.ui.geometry.Size(slotW * 0.65f, hM))
                            }
                        }
                        drawLine(Color.LightGray, androidx.compose.ui.geometry.Offset(0f, size.height),
                            androidx.compose.ui.geometry.Offset(size.width, size.height))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegendDot(OkGreen, "Taken")
                        LegendDot(TierCriticalColor, "Missed / skipped")
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Share with your clinician", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            try {
                                val pdf = com.aegismed.app.util.Exporters.buildPdf(context)
                                shareUri(context, com.aegismed.app.util.Exporters.sharePdfUri(context, pdf), "application/pdf", "Export adherence report")
                            } catch (_: Exception) {
                            }
                        }
                    }) { Text("Export PDF summary") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            try {
                                val csv = com.aegismed.app.util.Exporters.buildCsv(context)
                                shareUri(context, com.aegismed.app.util.Exporters.writeShareableCsv(context, csv), "text/csv", "Export dose log")
                            } catch (_: Exception) {
                            }
                        }
                    }) { Text("Export CSV dose log") }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Canvas(Modifier.height(10.dp).width(10.dp)) { drawRect(color) }
        Spacer(Modifier.padding(start = 6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun shareUri(context: android.content.Context, uri: android.net.Uri, mime: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(
        Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
