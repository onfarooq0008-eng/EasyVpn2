package com.fastvpn.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.fastvpn.app.ads.AdManager
import com.fastvpn.app.ads.AppOpenAdManager
import com.fastvpn.app.data.AppSettings

class FastVpnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyThemeMode()
        // Ads must not load/show before the user has accepted the consent
        // screen (ConsentActivity) -- otherwise a backgrounded app open ad
        // could appear over the consent screen itself, before the user has
        // agreed to anything. Returning users who already accepted get ads
        // initialized normally here; first-run users get them initialized
        // from ConsentActivity's "I Agree" button instead.
        if (AppSettings(this).hasAcceptedTerms) {
            AppOpenAdManager.attach(this)
            AdManager.init(this)
        }
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
