package com.easyvpn.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.easyvpn.app.R
import com.easyvpn.app.data.AppSettings
import com.easyvpn.app.databinding.ActivitySettingsBinding
import com.easyvpn.app.util.applyEdgeToEdgeInsets

/** Regular-user settings only. Admin-only actions (change admin password, server
 *  setup info) live in AdminPanelActivity, reachable only after the hidden
 *  long-press + password login -- they must never appear here where any user
 *  of the published app would see them. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)

        settings = AppSettings(this)

        binding.switchAutoConnect.isChecked = settings.autoConnectEnabled
        binding.switchAutoConnect.setOnCheckedChangeListener { _, checked ->
            settings.autoConnectEnabled = checked
        }

        // A real kill switch is Android's VPN lockdown mode. Apps cannot
        // programmatically enable it, so do not expose a local preference that
        // falsely claims the kill switch is active. The button below opens the
        // system VPN settings where the user can enable "Block connections
        // without VPN" for EasyVPN.


        // Android only lets the *user* (not the app itself) turn on true lockdown
        // mode; that's deliberate OS security design, not a library limitation.
        // We deep-link straight to the right screen to make it a two-tap job.
        binding.buttonSystemVpnSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }

        setUpThemeRadioGroup()
        setUpDnsSection()

        binding.buttonSplitTunneling.setOnClickListener {
            startActivity(Intent(this, SplitTunnelActivity::class.java))
        }

        binding.buttonDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
    }

    private fun setUpThemeRadioGroup() {
        val checkedId = when (settings.themeMode) {
            "light" -> R.id.radioThemeLight
            "dark" -> R.id.radioThemeDark
            else -> R.id.radioThemeSystem
        }
        binding.radioGroupTheme.check(checkedId)

        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedButtonId ->
            val (mode, nightMode) = when (checkedButtonId) {
                R.id.radioThemeLight -> "light" to AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioThemeDark -> "dark" to AppCompatDelegate.MODE_NIGHT_YES
                else -> "system" to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            settings.themeMode = mode
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    /** "Server default" plus one-tap presets (Google / Cloudflare / AdGuard ad-block)
     *  and a Custom option -- see AppSettings.resolveDns for how this is applied. */
    private fun setUpDnsSection() {
        val checkedId = when (settings.dnsMode) {
            "google" -> R.id.radioDnsGoogle
            "cloudflare" -> R.id.radioDnsCloudflare
            "adblock" -> R.id.radioDnsAdblock
            "custom" -> R.id.radioDnsCustom
            else -> R.id.radioDnsServer
        }
        binding.radioGroupDns.check(checkedId)
        binding.editCustomDns.setText(settings.customDns)
        binding.layoutCustomDns.visibility = if (checkedId == R.id.radioDnsCustom) View.VISIBLE else View.GONE

        binding.radioGroupDns.setOnCheckedChangeListener { _, checkedButtonId ->
            settings.dnsMode = when (checkedButtonId) {
                R.id.radioDnsGoogle -> "google"
                R.id.radioDnsCloudflare -> "cloudflare"
                R.id.radioDnsAdblock -> "adblock"
                R.id.radioDnsCustom -> "custom"
                else -> "server"
            }
            binding.layoutCustomDns.visibility = if (checkedButtonId == R.id.radioDnsCustom) View.VISIBLE else View.GONE
        }

        binding.buttonSaveCustomDns.setOnClickListener {
            settings.customDns = binding.editCustomDns.text.toString().trim()
            Toast.makeText(this, "Custom DNS saved", Toast.LENGTH_SHORT).show()
        }
    }
}
