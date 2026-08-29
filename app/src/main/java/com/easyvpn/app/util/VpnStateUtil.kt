package com.easyvpn.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Our own in-memory "are we connected" flag resets every time the app process
 * or Activity is recreated (backgrounded and killed by Android, screen
 * rotation, etc.) -- but the actual WireGuard tunnel keeps running
 * regardless, since it's owned by the OS-level VpnService, not by our
 * Activity. This checks Android's own record of whether a VPN is currently
 * active, so the UI can resync itself to reality instead of assuming "not
 * connected" just because the app was reopened.
 */
object VpnStateUtil {
    fun isSystemVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
