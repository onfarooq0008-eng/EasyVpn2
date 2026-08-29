package com.easyvpn.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.easyvpn.app.ads.AdManager
import com.easyvpn.app.data.AppSettings

class EasyVpnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyThemeMode()
        AdManager.init(this)
    }

    private fun applyThemeMode() {
        val mode = when (AppSettings(this).themeMode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
