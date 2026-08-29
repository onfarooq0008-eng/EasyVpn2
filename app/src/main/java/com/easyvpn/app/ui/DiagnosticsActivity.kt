package com.easyvpn.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.easyvpn.app.data.AppSettings
import com.easyvpn.app.data.ServerSource
import com.easyvpn.app.databinding.ActivityDiagnosticsBinding
import com.easyvpn.app.util.ConnectivityCheckUtil
import com.easyvpn.app.util.PingUtil
import com.easyvpn.app.util.VpnStateUtil
import com.easyvpn.app.vpn.VpnTunnelManagerHolder
import kotlinx.coroutines.launch
import com.easyvpn.app.util.applyEdgeToEdgeInsets

/**
 * On-demand diagnostics -- for the user to self-check ("why isn't this
 * working?") and for you to ask a confused user to screenshot when they
 * report a problem, instead of a back-and-forth of "what do you see".
 * Deliberately shows only non-sensitive info (no private keys).
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = "Connection diagnostics"

        binding.buttonRunDiagnostics.setOnClickListener { runDiagnostics() }
        runDiagnostics()
    }

    private fun runDiagnostics() {
        binding.buttonRunDiagnostics.isEnabled = false
        binding.textResults.text = "Running checks…"

        lifecycleScope.launch {
            val lines = mutableListOf<String>()
            val tunnelManager = VpnTunnelManagerHolder.get(this@DiagnosticsActivity)
            val systemVpnActive = VpnStateUtil.isSystemVpnActive(this@DiagnosticsActivity)
            tunnelManager.syncStateFromBackend()

            lines += "VPN interface: ${if (systemVpnActive) "UP" else "DOWN"}"

            if (systemVpnActive) {
                val working = ConnectivityCheckUtil.verifyInternetThroughVpnWithRetries(this@DiagnosticsActivity)
                lines += "Internet through tunnel: ${if (working) "OK ✓" else "FAILED ✗ (interface is up, but no traffic is actually getting through -- likely a server-side routing/firewall problem)"}"

                val stats = tunnelManager.statistics()
                if (stats != null) {
                    lines += "Data transferred this session: ↓${stats.totalRx()} bytes / ↑${stats.totalTx()} bytes"
                }
            } else {
                lines += "Internet through tunnel: N/A (not connected)"
            }

            val appSettings = AppSettings(this@DiagnosticsActivity)
            lines += ""
            lines += "Backend mode: ${if (appSettings.backendApiUrl.isNotBlank()) "ON (${appSettings.backendApiUrl})" else "OFF (local server list)"}"
            lines += "Last connected server id: ${appSettings.lastConnectedServerId ?: "none"}"
            lines += "Kill switch: Android system lockdown (check VPN settings)"
            lines += "DNS: ${appSettings.dnsMode} (${appSettings.resolveDns("server default")})"
            lines += "Split tunneling exclusions: ${appSettings.excludedPackages.size} app(s)"

            lines += ""
            lines += "Checking server list reachability…"
            binding.textResults.text = lines.joinToString("\n")

            try {
                val serverSource = ServerSource(this@DiagnosticsActivity)
                val servers = serverSource.getServers()
                lines += "Server list: fetched ${servers.size} server(s) successfully"
                if (servers.isNotEmpty()) {
                    val targets = servers.take(10).map { Triple(it.id, it.endpointHost, 22) }
                    val pings = PingUtil.pingAll(targets)
                    val reachable = pings.values.count { it >= 0 }
                    lines += "Reachability check: $reachable / ${targets.size} server(s) responded (sampled up to 10)"
                }
            } catch (e: Exception) {
                lines += "Server list: FAILED to fetch (${e.message})"
            }

            binding.textResults.text = lines.joinToString("\n")
            binding.buttonRunDiagnostics.isEnabled = true
        }
    }
}
