package com.easyvpn.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.easyvpn.app.util.NotificationHelper
import com.easyvpn.app.data.ServerSource
import com.easyvpn.app.util.SecureKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the "Disconnect" button on the persistent connected notification --
 * this can fire even if MainActivity isn't currently open, so it talks to
 * VpnTunnelManager directly rather than routing through the Activity. Uses
 * VpnTunnelManagerHolder to get the SAME shared instance the rest of the app
 * uses -- GoBackend tracks which tunnel handle is running as private instance
 * state, so a brand new GoBackend created here wouldn't actually have a real
 * handle to tear down, even after telling it the tunnel is up.
 */
class VpnActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISCONNECT = "com.easyvpn.app.ACTION_DISCONNECT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISCONNECT) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tunnelManager = VpnTunnelManagerHolder.get(context)
                // Do not manufacture a Tunnel object from the Android VPN UI
                // state. GoBackend owns the real Tunnel object and only that
                // object can be used to tear down the userspace WireGuard
                // tunnel. The holder gives this receiver the same manager
                // instance used by MainActivity.
                tunnelManager.syncStateFromBackend()
                val result = tunnelManager.disconnect()
                if (result.isSuccess) {
                    val appContext = context.applicationContext
                    val keyStore = SecureKeyStore(appContext)
                    val serverSource = ServerSource(appContext)
                    val active = keyStore.activeRegistration()
                    if (active != null) {
                        try {
                            serverSource.unregister(keyStore.clientPublicKeyBase64(), active.serverId, active.token)
                            keyStore.clearActiveRegistration()
                        } catch (_: Exception) {
                            // Keep the encrypted lease so the next app launch can retry cleanup.
                        }
                    }
                    NotificationHelper.clear(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
