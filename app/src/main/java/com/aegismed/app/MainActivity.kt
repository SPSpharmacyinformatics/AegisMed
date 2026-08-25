package com.aegismed.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlarmManager
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aegismed.app.notify.NotificationChannels
import com.aegismed.app.ui.AppNav
import com.aegismed.app.ui.AppViewModel
import com.aegismed.app.ui.theme.AegisTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var vm: AppViewModel
    private var nfcAdapter: NfcAdapter? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.ensure(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        vm = AppViewModel(application)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        promptExactAlarmIfNeeded()

        setContent {
            val fontScale by vm.fontScale.collectAsState()
            AegisTheme(fontScale = fontScale) {
                AppNav(activity = this, viewModel = vm)
            }
        }
    }

    private fun promptExactAlarmIfNeeded() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfc()
    }

    override fun onPause() {
        disableNfc()
        super.onPause()
    }

    private fun enableNfc() {
        val adapter = nfcAdapter ?: return
        runCatching {
            adapter.enableForegroundDispatch(
                this,
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    android.app.PendingIntent.FLAG_MUTABLE
                ),
                null,
                null
            )
        }
    }

    private fun disableNfc() {
        runCatching { nfcAdapter?.disableForegroundDispatch(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfc(intent)
    }

    override fun onPostResume() {
        super.onPostResume()
        intent?.let { handleNfc(it) }
    }

    private fun handleNfc(intent: Intent) {
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val hex = tag.id.joinToString("") { "%02X".format(it) }
        lifecycleScope.launch { vm.onNfcTagDiscovered(hex) }
    }
}
