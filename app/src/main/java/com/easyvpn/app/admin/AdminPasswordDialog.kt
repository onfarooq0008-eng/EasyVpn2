package com.easyvpn.app.admin

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Shown from AdminPanelActivity only -- i.e. only reachable by someone who already
 *  typed the current admin password once to get there. Regular users never see this. */
object AdminPasswordDialog {

    fun show(activity: Activity) {
        val currentField = EditText(activity).apply {
            hint = "Current password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newField = EditText(activity).apply {
            hint = "New password (min 6 chars)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(currentField)
            addView(newField)
        }

        AlertDialog.Builder(activity)
            .setTitle("Change admin password")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newPass = newField.text.toString()
                if (newPass.length < 6) {
                    Toast.makeText(activity, "New password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ok = AdminPasswordManager.changePassword(activity, currentField.text.toString(), newPass)
                Toast.makeText(
                    activity,
                    if (ok) "Admin password updated" else "Current password is incorrect",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
