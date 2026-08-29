package com.easyvpn.app.admin

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.easyvpn.app.data.AppSettings
import com.easyvpn.app.data.Server
import com.easyvpn.app.data.ServerRepository
import com.easyvpn.app.databinding.ActivityAdminPanelBinding
import kotlinx.coroutines.launch
import com.easyvpn.app.util.applyEdgeToEdgeInsets

class AdminPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminPanelBinding
    private lateinit var repo: ServerRepository
    private lateinit var appSettings: AppSettings
    private lateinit var adapter: AdminServerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)
        title = "Admin Panel — Manage Servers"

        repo = ServerRepository(this)
        appSettings = AppSettings(this)

        // Show the raw per-device override in the field (empty if none set),
        // not the resolved value -- otherwise it'd look like an override is
        // set even when the device is just using the built-in default.
        binding.editBackendApiUrl.setText(appSettings.rawBackendApiUrlOverride())
        updateActiveBackendLabel()

        binding.buttonSaveBackendUrl.setOnClickListener {
            appSettings.backendApiUrl = binding.editBackendApiUrl.text.toString()
            updateActiveBackendLabel()
            val message = if (appSettings.rawBackendApiUrlOverride().isBlank()) {
                "Override cleared — back to the built-in default"
            } else {
                "Override saved for this device only"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        binding.recyclerAdminServers.layoutManager = LinearLayoutManager(this)
        adapter = AdminServerAdapter(
            onEdit = { openEditor(it) },
            onDelete = { s -> repo.delete(s.id); refresh() },
            onToggleEnabled = { s -> s.enabled = !s.enabled; repo.update(s); refresh() }
        )
        binding.recyclerAdminServers.adapter = adapter

        binding.fabAddServer.setOnClickListener {
            startActivity(Intent(this, AddEditServerActivity::class.java))
        }

        binding.buttonSyncCloud.setOnClickListener {
            lifecycleScope.launch {
                val result = repo.syncFromCloud()
                result.onSuccess { count -> Toast.makeText(this@AdminPanelActivity, "Synced $count servers", Toast.LENGTH_SHORT).show() }
                result.onFailure { e -> Toast.makeText(this@AdminPanelActivity, "Sync failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                refresh()
            }
        }

        binding.buttonChangeAdminPassword.setOnClickListener {
            AdminPasswordDialog.show(this)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun openEditor(server: Server?) {
        val intent = Intent(this, AddEditServerActivity::class.java)
        server?.let { intent.putExtra(AddEditServerActivity.EXTRA_SERVER_ID, it.id) }
        startActivity(intent)
    }

    private fun refresh() {
        adapter.submit(repo.getAll())
    }

    private fun updateActiveBackendLabel() {
        val active = appSettings.backendApiUrl
        binding.textActiveBackend.text = when {
            active.isBlank() -> "Currently: no backend set — using the local server list below"
            appSettings.isUsingDefaultBackend() -> "Currently active (built-in default): $active"
            else -> "Currently active (override, this device only): $active"
        }
    }
}
