package com.fastvpn.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.fastvpn.app.R
import com.fastvpn.app.ads.AdManager
import com.fastvpn.app.data.Server
import com.fastvpn.app.databinding.ItemCountryBinding
import com.fastvpn.app.databinding.ItemNativeAdBinding
import com.fastvpn.app.databinding.ItemServerBinding
import com.google.android.gms.ads.nativead.NativeAd

private const val VIEW_TYPE_HEADER = 0
private const val VIEW_TYPE_SERVER = 1
private const val VIEW_TYPE_NATIVE_AD = 2

class HomeListAdapter(
    private val onHeaderClick: (CountryGroup) -> Unit,
    private val onServerClick: (Server) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<HomeRow>()
    private var connectedServerId: String? = null

    // Cache loaded native ads per slot so scrolling/refreshing the list doesn't burn
    // a fresh ad request every time; cleared ads are destroyed to avoid leaking webviews.
    private val nativeAdCache = mutableMapOf<Int, NativeAd>()

    fun submit(newItems: List<HomeRow>, connectedServerId: String?) {
        val oldItems = items.toList()
        val connectedIdChanged = this.connectedServerId != connectedServerId
        this.connectedServerId = connectedServerId
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                rowKey(oldItems[oldItemPosition]) == rowKey(newItems[newItemPosition])
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                // Connecting/disconnecting changes the "✓" prefix on a server row
                // without changing the Server data itself -- force a rebind on that
                // (rare) transition instead of risking a stale checkmark.
                if (connectedIdChanged) return false
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items.clear()
        items.addAll(newItems)
        // DiffUtil instead of notifyDataSetChanged(): now that the list also
        // auto-refreshes every 20s (see MainActivity), a full reset would rebind
        // every visible row -- including flashing native ads -- on every tick. This
        // only touches rows that actually changed, so everything else (and scroll
        // position) holds still.
        diff.dispatchUpdatesTo(this)
    }

    private fun rowKey(row: HomeRow): Any = when (row) {
        is HomeRow.Header -> "header_${row.group.countryCode}"
        is HomeRow.ServerRow -> "server_${row.server.id}"
        is HomeRow.NativeAdRow -> "ad_${row.slotId}"
    }

    /** Call from the host Activity's onDestroy to release native ad resources. */
    fun destroyAds() {
        nativeAdCache.values.forEach { it.destroy() }
        nativeAdCache.clear()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HomeRow.Header -> VIEW_TYPE_HEADER
        is HomeRow.ServerRow -> VIEW_TYPE_SERVER
        is HomeRow.NativeAdRow -> VIEW_TYPE_NATIVE_AD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderVH(ItemCountryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            VIEW_TYPE_NATIVE_AD -> NativeAdVH(ItemNativeAdBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> ServerVH(ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is HomeRow.Header -> (holder as HeaderVH).bind(row)
            is HomeRow.ServerRow -> (holder as ServerVH).bind(row.server)
            is HomeRow.NativeAdRow -> (holder as NativeAdVH).bind(row.slotId)
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

    inner class NativeAdVH(val b: ItemNativeAdBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(slotId: Int) {
            b.nativeAdView.headlineView = b.adHeadline
            b.nativeAdView.bodyView = b.adBody
            b.nativeAdView.iconView = b.adIcon
            b.nativeAdView.callToActionView = b.adCallToAction

            val cached = nativeAdCache[slotId]
            if (cached != null) {
                render(cached)
                return
            }
            b.adHeadline.text = ""
            b.adBody.text = ""
            b.adCallToAction.text = ""
            AdManager.loadNativeAd(b.root.context, onLoaded = { ad ->
                nativeAdCache[slotId] = ad
                // Guard against the row having been recycled/rebound to a different slot
                // by the time the async ad load finishes.
                if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                    (items.getOrNull(bindingAdapterPosition) as? HomeRow.NativeAdRow)?.slotId == slotId
                ) {
                    render(ad)
                }
            })
        }

        private fun render(ad: NativeAd) {
            b.adHeadline.text = ad.headline.orEmpty()
            b.adBody.text = ad.body.orEmpty()
            b.adCallToAction.text = ad.callToAction ?: "Learn more"
            val icon = ad.icon
            if (icon != null) {
                b.adIcon.setImageDrawable(icon.drawable)
                b.adIcon.visibility = View.VISIBLE
            } else {
                b.adIcon.visibility = View.GONE
            }
            b.nativeAdView.setNativeAd(ad)
        }
    }
}
