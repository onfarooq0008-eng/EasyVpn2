package com.easyvpn.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.easyvpn.app.R
import com.easyvpn.app.data.Server
import com.easyvpn.app.databinding.ItemCountryBinding
import com.easyvpn.app.databinding.ItemServerBinding

private const val VIEW_TYPE_HEADER = 0
private const val VIEW_TYPE_SERVER = 1

class HomeListAdapter(
    private val onHeaderClick: (CountryGroup) -> Unit,
    private val onServerClick: (Server) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<HomeRow>()
    private var connectedServerId: String? = null

    fun submit(newItems: List<HomeRow>, connectedServerId: String?) {
        items.clear()
        items.addAll(newItems)
        this.connectedServerId = connectedServerId
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HomeRow.Header -> VIEW_TYPE_HEADER
        is HomeRow.ServerRow -> VIEW_TYPE_SERVER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderVH(ItemCountryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            ServerVH(ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is HomeRow.Header -> (holder as HeaderVH).bind(row)
            is HomeRow.ServerRow -> (holder as ServerVH).bind(row.server)
        }
    }

    override fun getItemCount() = items.size

    private fun bindStatus(context: android.content.Context, statusView: TextView, msView: TextView, pingMs: Int) {
        when {
            pingMs == -1 -> {
                statusView.text = "Checking…"
                statusView.setTextColor(ContextCompat.getColor(context, R.color.statusChecking))
                msView.text = ""
            }
            pingMs == -2 -> {
                statusView.text = "Offline"
                statusView.setTextColor(ContextCompat.getColor(context, R.color.statusOffline))
                msView.text = ""
            }
            else -> {
                statusView.text = "Online"
                statusView.setTextColor(ContextCompat.getColor(context, R.color.statusOnline))
                msView.text = "${pingMs} ms"
            }
        }
    }

    inner class HeaderVH(val b: ItemCountryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: HomeRow.Header) {
            val group = row.group
            b.textFlag.text = group.flagEmoji()
            b.textCountryName.text = group.countryName
            b.textServerCount.text = if (group.servers.size == 1) "1 server" else "${group.servers.size} servers"
            bindStatus(b.root.context, b.textBestStatus, b.textBestPingMs, group.bestPingMs())
            b.textChevron.text = if (row.expanded) "⌄" else "›"
            b.textChevron.visibility = if (group.servers.size > 1) View.VISIBLE else View.GONE
            b.root.setOnClickListener { onHeaderClick(group) }
        }
    }

    inner class ServerVH(val b: ItemServerBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(server: Server) {
            b.textFlag.text = server.flagEmoji()
            val isConnected = connectedServerId == server.id
            val displayName = server.name.ifBlank { server.countryName }
            b.textName.text = if (isConnected) "✓ $displayName" else displayName
            b.textCity.text = if (server.city.isNotBlank()) "${server.countryName} • ${server.city}" else server.countryName
            bindStatus(b.root.context, b.textStatus, b.textPingMs, server.pingMs)

            b.root.setOnClickListener { onServerClick(server) }
        }
    }
}
