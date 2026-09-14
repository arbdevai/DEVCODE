package com.devcode.terminal

import android.app.Application
import com.devcode.terminal.core.ubuntu.UbuntuManager
import com.devcode.terminal.data.SettingsRepository

class DevCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        UbuntuManager.init(this)
    }

    companion object {
        lateinit var settings: SettingsRepository
            private set
    }
}
