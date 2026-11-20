package com.example.domain.model

enum class DnsProvider(val label: String, val primaryIp: String, val secondaryIp: String) {
    CLOUDFLARE("Cloudflare 1.1.1.1", "1.1.1.1", "1.0.0.1"),
    QUAD9("Quad9 Security", "9.9.9.9", "149.112.112.112"),
    GOOGLE("Google Public DNS", "8.8.8.8", "8.8.4.4")
}

data class VpnSettings(
    val autoFastSwitchEnabled: Boolean = true,
    val latencyThresholdMs: Int = 220,
    val preferWireGuardEngine: Boolean = true,
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
    val splitTunnelExcludeLan: Boolean = true,
    val customWireGuardConfig: String = ""
)
