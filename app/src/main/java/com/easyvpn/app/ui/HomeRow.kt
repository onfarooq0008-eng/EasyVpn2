package com.easyvpn.app.ui

import com.easyvpn.app.data.Server

sealed class HomeRow {
    data class Header(val group: CountryGroup, val expanded: Boolean) : HomeRow()
    data class ServerRow(val server: Server) : HomeRow()
}
