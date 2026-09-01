package com.fastvpn.app.ui

import com.fastvpn.app.data.Server

sealed class HomeRow {
    data class Header(val group: CountryGroup, val expanded: Boolean) : HomeRow()
    data class ServerRow(val server: Server) : HomeRow()
    // slotId lets the adapter give each ad slot position a stable identity across
    // notifyDataSetChanged() calls so it doesn't reload a fresh native ad on every refresh.
    data class NativeAdRow(val slotId: Int) : HomeRow()
}
