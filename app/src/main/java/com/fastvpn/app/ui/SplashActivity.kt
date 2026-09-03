package com.fastvpn.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fastvpn.app.data.AppSettings

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (AppSettings(this).hasAcceptedTerms) {
            MainActivity::class.java
        } else {
            ConsentActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
