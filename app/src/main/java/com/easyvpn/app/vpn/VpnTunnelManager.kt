package com.easyvpn.app.vpn

import android.content.Context
import com.easyvpn.app.data.Server
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class TunnelState { DOWN, CONNECTING, UP }

/**
 * Thin wrapper around the official com.wireguard.android GoBackend.
 * The client's own private key is generated once on-device and never leaves it.
 * In production/backend mode the control API allocates the client tunnel IP
 * atomically and returns it with the server configuration.
 */
class VpnTunnelManager(private val context: Context) {

    companion object {
        const val TUNNEL_NAME = "easyvpn"
    }

    private val backend = GoBackend(context)
    private var currentTunnel: SimpleTunnel? = null
    private val operationMutex = Mutex()

    var state: TunnelState = TunnelState.DOWN
        private set

    private class SimpleTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) { /* observed via manager */ }
    }

    /** Call once and persist the returned key pair (store private key securely, e.g. EncryptedSharedPreferences). */
    fun generateKeyPair(): com.wireguard.crypto.KeyPair = com.wireguard.crypto.KeyPair()

    suspend fun connect(
        server: Server,
        clientPrivateKeyBase64: String,
        excludedPackages: Set<String> = emptySet(),
        assignedAddressCidr: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
        try {
            state = TunnelState.CONNECTING
            val peerPublicKey = com.wireguard.crypto.Key.fromBase64(server.serverPublicKey)

            // The tunnel address MUST come from the server-side allocator.
            // Client-side/hash-derived allocation was removed because it can collide
            // between different devices and is not authoritative for WireGuard peers.
            require(assignedAddressCidr.isNotBlank()) {
                "A server-assigned tunnel address is required"
            }

            val ifaceBuilder = Interface.Builder()
                .parsePrivateKey(clientPrivateKeyBase64)
                .parseAddresses(assignedAddressCidr)
                .parseDnsServers(server.dns)

            if (excludedPackages.isNotEmpty()) {
                ifaceBuilder.excludeApplications(excludedPackages)
            }

            val peerBuilder = Peer.Builder()
                .setPublicKey(peerPublicKey)
                .parseAllowedIPs("0.0.0.0/0")
                .parseEndpoint(server.endpoint)
                .setPersistentKeepalive(25)

            if (server.presharedKey.isNotBlank()) {
                peerBuilder.parsePreSharedKey(server.presharedKey)
            }

            val config = Config.Builder()
                .setInterface(ifaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()

            val tunnel = SimpleTunnel(TUNNEL_NAME)
            backend.setState(tunnel, Tunnel.State.UP, config)
            currentTunnel = tunnel
            state = TunnelState.UP
            Result.success(Unit)
        } catch (e: Exception) {
            state = TunnelState.DOWN
            Result.failure(e)
        }
        }
    }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
        try {
            currentTunnel?.let { backend.setState(it, Tunnel.State.DOWN, null) }
            currentTunnel = null
            state = TunnelState.DOWN
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
        }
    }

    /**
     * Synchronize our in-memory state with this manager's GoBackend.
     *
     * GoBackend deliberately keeps the active Tunnel as an object reference.
     * Therefore we must never manufacture a new SimpleTunnel merely because
     * Android reports that some VPN interface is active: doing so creates a
     * fake handle that GoBackend does not own and makes disconnect() a no-op.
     *
     * VpnTunnelManagerHolder guarantees that all app components in the same
     * process use this manager/backend instance. If the process itself was
     * killed, the GoBackend/VpnService state is gone as well and the correct
     * state for a new manager is DOWN.
     */
    fun syncStateFromBackend(): TunnelState {
        return try {
            val running = backend.getRunningTunnelNames().contains(TUNNEL_NAME)
            state = if (running) {
                // GoBackend identifies tunnels by name. Recreate the lightweight
                // Tunnel descriptor after Activity/process recreation so the Home
                // screen and notification can still control the running tunnel.
                if (currentTunnel == null) currentTunnel = SimpleTunnel(TUNNEL_NAME)
                TunnelState.UP
            } else {
                currentTunnel = null
                TunnelState.DOWN
            }
            state
        } catch (_: Exception) {
            state = if (currentTunnel != null) TunnelState.UP else TunnelState.DOWN
            state
        }
    }


    fun statistics() = currentTunnel?.let { runCatching { backend.getStatistics(it) }.getOrNull() }
}
