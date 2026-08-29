package com.easyvpn.app.data

import android.content.Context

/** Simple app-level toggles surfaced in SettingsActivity. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("easyvpn_settings", Context.MODE_PRIVATE)

    /** When on, connect to the last-used (or fastest) server automatically on app launch. */
    var autoConnectEnabled: Boolean
        get() = prefs.getBoolean("auto_connect", false)
        set(value) = prefs.edit().putBoolean("auto_connect", value).apply()

    var lastConnectedServerId: String?
        get() = prefs.getString("last_server_id", null)
        set(value) = prefs.edit().putString("last_server_id", value).apply()

    /** "system", "light", or "dark". */
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    /** Package names of apps that should bypass the VPN (split tunneling). */
    var excludedPackages: Set<String>
        get() = prefs.getStringSet("excluded_packages", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("excluded_packages", value).apply()

    /**
     * URL of your registration backend (see /backend in this project), e.g.
     * "https://api.yourdomain.com". When set, the app fetches the server list
     * and its own per-device tunnel config from this API instead of the local
     * Admin Panel list, and registration with a VPS happens automatically --
     * no manual add-client.sh needed per user.
     *
     * Reads from a per-device override first (set via Admin Panel -> Backend
     * API URL, handy for testing a different backend on your own device).
     * If that's blank, falls back to BuildConfig.DEFAULT_BACKEND_API_URL --
     * the value baked in at build time (app/build.gradle), which is what
     * every real user of the published app gets automatically with zero
     * setup on their end. Leave both blank to use the manual Admin Panel +
     * add-client.sh flow instead (fine for a small beta).
     */
    var backendApiUrl: String
        get() {
            val override = prefs.getString("backend_api_url", "") ?: ""
            return override.ifBlank { com.easyvpn.app.BuildConfig.DEFAULT_BACKEND_API_URL }
        }
        set(value) = prefs.edit().putString("backend_api_url", value.trim()).apply()

    /** True if this device is using the compiled-in default rather than a manual override. */
    fun isUsingDefaultBackend(): Boolean = (prefs.getString("backend_api_url", "") ?: "").isBlank()

    /**
     * DNS used inside the tunnel. "server" (default) keeps whatever the connected
     * server itself specifies (Server.dns / the backend's per-server default).
     * The rest override it: "google", "cloudflare", "adblock" (AdGuard DNS -- blocks
     * ads/trackers), or "custom" (see [customDns]). Applied on top of the server's
     * own value right before connecting -- see [resolveDns].
     */
    var dnsMode: String
        get() = prefs.getString("dns_mode", "server") ?: "server"
        set(value) = prefs.edit().putString("dns_mode", value).apply()

    /** User-entered DNS server(s) for dnsMode == "custom", e.g. "9.9.9.9" or "9.9.9.9, 149.112.112.112". */
    var customDns: String
        get() = prefs.getString("custom_dns", "") ?: ""
        set(value) = prefs.edit().putString("custom_dns", value.trim()).apply()

    /** Resolves [dnsMode] against the connecting server's own default ([serverDns]).
     *  Falls back to [serverDns] for "server" mode, and also for "custom" mode if
     *  no custom value has been saved yet (so an empty custom field never breaks
     *  the connection -- it just behaves like "server default" until filled in). */
    fun resolveDns(serverDns: String): String = when (dnsMode) {
        "google" -> "8.8.8.8, 8.8.4.4"
        "cloudflare" -> "1.1.1.1, 1.0.0.1"
        "adblock" -> "94.140.14.14, 94.140.15.15" // AdGuard DNS Default -- ad/tracker blocking, no signup needed
        "custom" -> customDns.ifBlank { serverDns }
        else -> serverDns
    }

    /** The raw per-device override only (empty if none set) -- unlike [backendApiUrl], this does
     *  NOT fall back to the compiled-in default. Used by the Admin Panel to show what's actually
     *  been typed into the override field, without duplicating the underlying pref name/key
     *  elsewhere in the codebase (which would silently drift if this ever changed here). */
    fun rawBackendApiUrlOverride(): String = prefs.getString("backend_api_url", "") ?: ""
}
