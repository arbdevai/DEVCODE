package com.devcode.terminal

import android.app.Application
import com.devcode.terminal.core.chroot.ChrootManager
import com.devcode.terminal.core.ubuntu.UbuntuManager
import com.devcode.terminal.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DevCodeApp : Application() {
    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        UbuntuManager.init(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            appScope.launch {
                try {
                    ChrootManager.listSessions() // prune dead sessions
                } catch (_: Throwable) {}
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        appScope.launch {
            try {
                ChrootManager.listSessions()
            } catch (_: Throwable) {}
        }
    }

    companion object {
        lateinit var settings: SettingsRepository
            private set
    }
}
