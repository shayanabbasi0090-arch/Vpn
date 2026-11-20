package com.example.domain.model

enum class TunnelEngineType(val label: String) {
    WIREGUARD_NOISE("WireGuard Noise-IK"),
    SOCKS5_TUNNEL("SOCKS5 Remote IP Tunnel"),
    ANDROID_VPN_TUN("DoH Shield Packet Tunnel")
}

sealed class VpnConnectionState {
    data class Disconnected(
        val lastReason: String? = null
    ) : VpnConnectionState()

    data class Connecting(
        val server: VpnServer,
        val stepLabel: String
    ) : VpnConnectionState()

    data class Connected(
        val server: VpnServer,
        val engineType: TunnelEngineType,
        val assignedIp: String,
        val connectedAtMillis: Long
    ) : VpnConnectionState()

    data class Reconnecting(
        val fromServer: VpnServer?,
        val toServer: VpnServer,
        val reason: String
    ) : VpnConnectionState()

    data class Error(
        val message: String,
        val lastServer: VpnServer? = null
    ) : VpnConnectionState()
}
