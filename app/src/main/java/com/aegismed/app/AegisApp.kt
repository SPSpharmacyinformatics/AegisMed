package com.aegismed.app

import android.app.Application
import com.aegismed.app.notify.AlarmPlanner
import com.aegismed.app.notify.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AegisApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        scope.launch {
            try {
                AlarmPlanner.armNext(this@AegisApp)
            } catch (_: Exception) {
            }
        }
    }
}
