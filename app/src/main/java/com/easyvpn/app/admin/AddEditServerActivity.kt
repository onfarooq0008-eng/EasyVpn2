package com.easyvpn.app.admin

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.easyvpn.app.data.Server
import com.easyvpn.app.data.ServerRepository
import com.easyvpn.app.databinding.ActivityAddEditServerBinding
import com.easyvpn.app.util.applyEdgeToEdgeInsets

class AddEditServerActivity : AppCompatActivity() {

    companion object { const val EXTRA_SERVER_ID = "server_id" }

    private lateinit var binding: ActivityAddEditServerBinding
    private lateinit var repo: ServerRepository
    private var editingServer: Server? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEditServerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)
        repo = ServerRepository(this)

        val id = intent.getStringExtra(EXTRA_SERVER_ID)
        editingServer = repo.getAll().find { it.id == id }
        editingServer?.let { fillForm(it) }
        title = if (editingServer != null) "Edit server" else "Add new server (your VPS)"

        binding.buttonSave.setOnClickListener { save() }
    }

    private fun fillForm(s: Server) {
        binding.editName.setText(s.name)
        binding.editCountryName.setText(s.countryName)
        binding.editCountryCode.setText(s.countryCode)
        binding.editCity.setText(s.city)
        binding.editHost.setText(s.endpointHost)
        binding.editPort.setText(s.endpointPort.toString())
        binding.editPublicKey.setText(s.serverPublicKey)
        binding.editPresharedKey.setText(s.presharedKey)
        binding.editClientAddress.setText(s.clientAddress)
        binding.editDns.setText(s.dns)
    }

    private fun save() {
        val host = binding.editHost.text.toString().trim()
        val pubKey = binding.editPublicKey.text.toString().trim()
        if (host.isBlank() || pubKey.isBlank()) {
            Toast.makeText(this, "Host (your VPS IP) and WireGuard public key are required", Toast.LENGTH_LONG).show()
            return
        }

        val server = editingServer ?: Server()
        server.name = binding.editName.text.toString().trim()
        server.countryName = binding.editCountryName.text.toString().trim()
        server.countryCode = binding.editCountryCode.text.toString().trim().uppercase().ifBlank { "US" }
        server.city = binding.editCity.text.toString().trim()
        server.endpointHost = host
        server.endpointPort = binding.editPort.text.toString().toIntOrNull() ?: 51820
        server.serverPublicKey = pubKey
        server.presharedKey = binding.editPresharedKey.text.toString().trim()
        server.clientAddress = binding.editClientAddress.text.toString().trim().ifBlank { "10.8.0.0/24" }
        server.dns = binding.editDns.text.toString().trim().ifBlank { "1.1.1.1" }

        if (editingServer != null) repo.update(server) else repo.add(server)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
