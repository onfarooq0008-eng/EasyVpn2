package com.easyvpn.app.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.easyvpn.app.data.Server
import com.easyvpn.app.databinding.ItemAdminServerBinding

class AdminServerAdapter(
    private val onEdit: (Server) -> Unit,
    private val onDelete: (Server) -> Unit,
    private val onToggleEnabled: (Server) -> Unit
) : RecyclerView.Adapter<AdminServerAdapter.VH>() {

    private val items = mutableListOf<Server>()

    fun submit(newItems: List<Server>) {
        items.clear(); items.addAll(newItems); notifyDataSetChanged()
    }

    inner class VH(val binding: ItemAdminServerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAdminServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val b = holder.binding
        b.textAdminName.text = "${s.flagEmoji()} ${s.name} (${s.countryName})"
        b.textAdminEndpoint.text = "${s.endpoint} → key: ${s.serverPublicKey.take(12)}…"
        b.switchEnabled.setOnCheckedChangeListener(null)
        b.switchEnabled.isChecked = s.enabled
        b.switchEnabled.setOnCheckedChangeListener { _, _ -> onToggleEnabled(s) }

        b.buttonEdit.setOnClickListener { onEdit(s) }
        b.buttonDelete.setOnClickListener { onDelete(s) }
    }

    override fun getItemCount() = items.size
}
