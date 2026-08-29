package com.easyvpn.app.admin

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Single source of truth for the admin password, used by both AdminLoginActivity
 * and the "change admin password" flow in Settings so there's no duplicated logic.
 */
object AdminPasswordManager {

    // First-run default -- CHANGE THIS before publishing (or change it once via
    // Settings -> Change admin password after your first login, then this
    // constant is never used again since a real hash gets stored).
    private const val DEFAULT_PASSWORD = "changeme123"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, "easyvpn_admin_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun check(context: Context, entered: String): Boolean {
        val stored = prefs(context).getString("admin_pass_hash", null)
            ?: return entered == DEFAULT_PASSWORD
        return sha256(entered) == stored
    }

    /** Returns false if currentPassword doesn't match, true once updated. */
    fun changePassword(context: Context, currentPassword: String, newPassword: String): Boolean {
        if (!check(context, currentPassword)) return false
        prefs(context).edit().putString("admin_pass_hash", sha256(newPassword)).apply()
        return true
    }
}
