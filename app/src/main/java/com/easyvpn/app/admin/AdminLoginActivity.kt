package com.easyvpn.app.admin

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.easyvpn.app.databinding.ActivityAdminLoginBinding
import com.easyvpn.app.util.applyEdgeToEdgeInsets

/**
 * Simple password gate so random users who long-press the version text can't
 * get into your server admin panel. Default password is "changeme123" --
 * change it from Settings -> Change admin password right after first login.
 */
class AdminLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)

        binding.buttonLogin.setOnClickListener {
            val entered = binding.editPassword.text.toString()
            if (AdminPasswordManager.check(this, entered)) {
                startActivity(Intent(this, AdminPanelActivity::class.java))
                finish()
            } else {
                binding.editPassword.error = "Wrong password"
            }
        }
    }
}
