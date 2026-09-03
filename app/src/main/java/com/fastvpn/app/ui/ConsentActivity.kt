package com.fastvpn.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fastvpn.app.ads.AdManager
import com.fastvpn.app.ads.AppOpenAdManager
import com.fastvpn.app.data.AppSettings
import com.fastvpn.app.databinding.ActivityConsentBinding
import com.fastvpn.app.util.applyEdgeToEdgeInsets

/**
 * Shown once, before MainActivity is ever reached, on first launch. Gates
 * access behind explicit acceptance of the Privacy Policy / Terms -- required
 * for Play Store review of any VPN app (BIND_VPN_SERVICE is a sensitive
 * permission) and for AdMob. See backend/api/public/privacy.html and
 * terms.html for the actual policy text (served at
 * https://api.fastvpnn.pp.ua/privacy.html and /terms.html).
 *
 * The close (X) button declines -- since using the app at all requires
 * accepting how it handles your traffic, declining just closes the app
 * rather than pretending there's a way to use it without agreeing.
 */
class ConsentActivity : AppCompatActivity() {

    private val policyUrl = "https://api.fastvpnn.pp.ua/privacy.html"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)

        binding.linkTerms.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(policyUrl)))
        }

        binding.buttonClose.setOnClickListener {
            finishAffinity()
        }

        binding.buttonAgree.setOnClickListener {
            AppSettings(this).hasAcceptedTerms = true
            AppOpenAdManager.attach(application)
            AdManager.init(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
