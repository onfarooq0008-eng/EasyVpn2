package com.easyvpn.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Single source of truth for the server list.
 *
 * Servers are stored locally in SharedPreferences as JSON (so the Admin Panel
 * works fully offline / with no backend). Optionally, set REMOTE_SERVERS_URL
 * to a JSON file you host (e.g. a raw GitHub Gist/file on one of your VPS) so
 * you can push new servers to all installed apps without a Play Store update.
 * When set, "Sync from cloud" in the Admin Panel merges remote servers in.
 */
class ServerRepository(context: Context) {

    private val prefs = context.getSharedPreferences("easyvpn_servers", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<Server>>() {}.type

    companion object {
        private const val KEY_SERVERS = "servers_json"

        // TODO: point this at a servers.json you host yourself, or leave blank to manage
        // everything from the in-app Admin Panel only.
        const val REMOTE_SERVERS_URL = ""
    }

    fun getAll(): MutableList<Server> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return seedDefaults()
        return try {
            gson.fromJson(json, listType) ?: seedDefaults()
        } catch (e: Exception) {
            seedDefaults()
        }
    }

    fun saveAll(servers: List<Server>) {
        prefs.edit().putString(KEY_SERVERS, gson.toJson(servers)).apply()
    }

    fun add(server: Server) {
        val all = getAll()
        all.add(server)
        saveAll(all)
    }

    fun update(server: Server) {
        val all = getAll()
        val idx = all.indexOfFirst { it.id == server.id }
        if (idx >= 0) all[idx] = server else all.add(server)
        saveAll(all)
    }

    fun delete(serverId: String) {
        val all = getAll()
        all.removeAll { it.id == serverId }
        saveAll(all)
    }

    /** No hard-coded limit on how many servers you can add -- the Admin Panel's "+"
     *  button works for 6, 60, or 600 servers, each just an entry in this list.
     *  First run starts empty (rather than fake placeholder servers that would
     *  permanently show as "offline" and look like a bug) so the home screen's
     *  empty-state message points you straight at the Admin Panel instead. */
    private fun seedDefaults(): MutableList<Server> = mutableListOf()

    /** Pulls servers.json from REMOTE_SERVERS_URL and merges by id (admin-triggered). */
    suspend fun syncFromCloud(): Result<Int> = withContext(Dispatchers.IO) {
        if (REMOTE_SERVERS_URL.isBlank()) return@withContext Result.failure(
            IllegalStateException("No REMOTE_SERVERS_URL configured")
        )
        try {
            val client = OkHttpClient()
            val req = Request.Builder().url(REMOTE_SERVERS_URL).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body?.string() ?: throw IOException("Empty body")
                val remote: List<Server> = gson.fromJson(body, listType)
                val local = getAll()
                remote.forEach { r ->
                    val idx = local.indexOfFirst { it.id == r.id }
                    if (idx >= 0) local[idx] = r else local.add(r)
                }
                saveAll(local)
                Result.success(remote.size)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
