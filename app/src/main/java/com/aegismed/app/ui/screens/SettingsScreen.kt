package com.aegismed.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegismed.app.ui.AppViewModel
import com.aegismed.app.util.BackupManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    activity: ComponentActivity,
    nav: androidx.navigation.NavHostController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fontScale by vm.fontScale.collectAsState()
    val night by vm.nightRoutine.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val online by vm.onlineLookups.collectAsState()

    var showAddContact by remember { mutableStateOf(false) }
    var backupPass by remember { mutableStateOf("") }
    var restorePass by remember { mutableStateOf("") }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var statusMsg by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && backupPass.length >= 8) {
            scope.launch {
                val r = BackupManager.exportEncrypted(context, backupPass.toCharArray(), uri)
                statusMsg = if (r.isSuccess) "Encrypted backup saved (${r.getOrNull()} bytes)"
                else "Backup failed: ${r.exceptionOrNull()?.message}"
                backupPass = ""
            }
        } else if (uri != null) {
            statusMsg = "Passphrase must be at least 8 characters"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
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
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Accessibility", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Text size: ${"%.0f".format(fontScale * 100)}%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = fontScale, onValueChange = { vm.setFontScale(it) },
                        valueRange = 0.9f..1.6f, steps = 6)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Night-shift routine", fontWeight = FontWeight.SemiBold)
                            Text("Shifts anchor defaults for shift workers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = night, onCheckedChange = { vm.toggleNightRoutine() })
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Caregiver escalation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Alerts are sent when a critical dose stays unacknowledged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    contacts.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontWeight = FontWeight.SemiBold)
                                Text(c.address, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { vm.deleteContact(c.id) }) { Text("Remove") }
                        }
                    }

                    Button(onClick = { showAddContact = true }) { Text("Add contact") }
                    Text("SEND_SMS permission is requested only when contacts are configured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Clinical data sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Online lookups (RxNorm · openFDA)", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Autofill + interaction sync. Requests go only to nlm.nih.gov and fda.gov; findings are cached in your encrypted vault for offline use.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = online, onCheckedChange = { vm.setOnlineLookups(it) })
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Zero-knowledge backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Your data is encrypted with a key derived from your passphrase (PBKDF2 · AES-256-GCM). " +
                            "No unencrypted data ever leaves this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(value = backupPass, onValueChange = { backupPass = it },
                        label = { Text("Backup passphrase") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        exportLauncher.launch("aegismed-backup-${System.currentTimeMillis()}.aegis")
                    }) { Text("Create encrypted backup") }

                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        importLauncher.launch(arrayOf("*/*", "application/octet-stream"))
                    }) { Text("Restore from backup") }

                    if (statusMsg.isNotEmpty()) {
                        Text(statusMsg, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }

    if (showAddContact) {
        var cname by remember { mutableStateOf("") }
        var caddr by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddContact = false },
            title = { Text("Add care contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(cname, { cname = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(caddr, { caddr = it },
                        label = { Text("Phone number") }, singleLine = true)
                    Text("Contact receives SMS after 45 min of an unconfirmed critical dose.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (cname.isNotBlank() && caddr.isNotBlank()) {
                        vm.addContact(cname.trim(), caddr.trim(), smsChannel = true)
                        showAddContact = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddContact = false }) { Text("Cancel") }
            }
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Restore encrypted backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(restorePass, { restorePass = it },
                        label = { Text("Passphrase") }, singleLine = true)
                    Text("Restoring merges the backup into your local vault.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = BackupManager.restoreEncrypted(context, restorePass.toCharArray(), uri)
                        statusMsg = if (r.isSuccess) "Restored ${r.getOrNull()} medications"
                        else "Restore failed: ${r.exceptionOrNull()?.message}"
                        pendingRestoreUri = null
                        restorePass = ""
                        vm.refresh()
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("Cancel") }
            }
        )
    }
}
