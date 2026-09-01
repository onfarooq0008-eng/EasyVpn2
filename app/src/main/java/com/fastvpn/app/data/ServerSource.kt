package com.fastvpn.app.data

import android.content.Context

/**
 * Single place that talks to the backend control API (either the compiled-in
 * default from app/build.gradle, or a per-device override). Everything else
 * (home screen, country screen) just calls getServers() and doesn't need to
 * know the URL.
 */
class ServerSource(context: Context) {
    private val appSettings = AppSettings(context)
    private val backendClient = BackendApiClient()

    suspend fun getServers(): List<Server> {
        val backendUrl = appSettings.backendApiUrl
        return try {
            backendClient.fetchServers(backendUrl)
        } catch (e: Exception) {
            // Backend is authoritative. Do not silently fall back to a local
            // list because those servers cannot be registered through the backend.
            emptyList()
        }
    }

    suspend fun register(devicePublicKeyBase64: String, preferredServerId: String?): BackendRegistration {
        val backendUrl = appSettings.backendApiUrl
        return backendClient.register(backendUrl, devicePublicKeyBase64, preferredServerId)
    }
    suspend fun unregister(devicePublicKeyBase64: String, serverId: String, registrationToken: String) {
        val backendUrl = appSettings.backendApiUrl
        backendClient.unregister(backendUrl, devicePublicKeyBase64, serverId, registrationToken)
    }

}
