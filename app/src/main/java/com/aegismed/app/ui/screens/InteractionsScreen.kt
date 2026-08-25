package com.aegismed.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton
import com.aegismed.app.ui.AppViewModel
import com.aegismed.app.ui.theme.OkGreen
import com.aegismed.app.ui.theme.TierCriticalColor
import com.aegismed.app.ui.theme.TierStandardColor

private fun severityColor(sev: String) = when (sev) {
    "contraindicated", "major" -> TierCriticalColor
    "moderate" -> TierStandardColor
    else -> OkGreen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractionsScreen(vm: AppViewModel, nav: androidx.navigation.NavHostController) {
    val state by vm.dashboard.collectAsState()
    var syncing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Interaction engine", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(enabled = !syncing, onClick = {
                        syncing = true
                        vm.refreshRemoteInteractions { syncing = false }
                    }) {
                        Text(if (syncing) "Syncing…" else "Sync NIH")
                    }
                }
            )
        }
    ) { pad ->
        val results = state.interactions
        if (results.isEmpty()) {
            Column(Modifier.padding(pad).padding(24.dp)) {
                Text("No interactions found",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your active medications were cross-checked against 50+ clinical rules covering " +
                        "drug–drug, drug–food (grapefruit, dairy, vitamin K), herbal supplements and alcohol. All clear.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "${results.size} finding(s) — checked on-device against bundled clinical rules",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items(results, key = { it.ruleId + it.counterpartLabel }) { r ->
                    val color = severityColor(r.severity)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(r.severity.uppercase(), color = color, fontWeight = FontWeight.ExtraBold)
                            Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Source: ${r.source}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            if (r.counterpartLabel.isNotBlank()) {
                                Text("Involves: ${r.counterpartLabel}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(r.description, style = MaterialTheme.typography.bodyMedium)
                            r.mechanism?.let {
                                Text("Mechanism: $it", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}
