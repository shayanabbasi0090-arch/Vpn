package com.example.domain.model

import com.squareup.moshi.JsonClass

enum class ServerProtocol(val displayLabel: String) {
    WIREGUARD("WireGuard"),
    SOCKS5_TUN("SOCKS5 Tunnel"),
    RELAY_TUN("DoH Shield TUN")
}

@JsonClass(generateAdapter = true)
data class VpnServer(
    val id: String,
    val name: String,
    val countryCode: String,
    val countryName: String,
    val city: String,
    val host: String,
    val port: Int,
    val protocol: ServerProtocol,
    val pingMs: Int? = null, // null = untested, -1 = timeout/unreachable, >0 = live RTT
    val bandwidthMbps: Double = 0.0,
    val loadPercent: Int = 25,
    val sessions: Int = 0,
    val score: Long = 0L,
    val source: String = "Public Directory",
    val wireGuardConfig: String? = null,
    val openVpnConfigBase64: String? = null,
    val isSmartAuto: Boolean = false,
    val isFavorite: Boolean = false
) {
    val flagEmoji: String
        get() = countryCodeToFlag(countryCode)

    val pingTier: PingTier
        get() = when {
            pingMs == null -> PingTier.UNTESTED
            pingMs < 0 -> PingTier.UNREACHABLE
            pingMs <= 80 -> PingTier.FAST
            pingMs <= 160 -> PingTier.MEDIUM
            else -> PingTier.SLOW
        }

    companion object {
        const val SMART_AUTO_ID = "smart_auto_fastest"

        fun smartAutoPlaceholder(fastestResolved: VpnServer? = null): VpnServer {
            return VpnServer(
                id = SMART_AUTO_ID,
                name = fastestResolved?.let { "Fastest • ${it.countryName}" } ?: "Smart Auto-Connect",
                countryCode = fastestResolved?.countryCode ?: "AUTO",
                countryName = fastestResolved?.countryName ?: "Auto Fastest",
                city = fastestResolved?.city ?: "Lowest Latency Edge",
                host = fastestResolved?.host ?: "engage.cloudflareclient.com",
                port = fastestResolved?.port ?: 2408,
                protocol = fastestResolved?.protocol ?: ServerProtocol.WIREGUARD,
                pingMs = fastestResolved?.pingMs,
                bandwidthMbps = fastestResolved?.bandwidthMbps ?: 1000.0,
                loadPercent = fastestResolved?.loadPercent ?: 12,
                source = "Smart Optimizer",
                isSmartAuto = true
            )
        }

        fun countryCodeToFlag(code: String): String {
            val clean = code.trim().uppercase()
            if (clean == "AUTO" || clean.length != 2) return "🌐"
            val first = clean[0]
            val second = clean[1]
            if (first !in 'A'..'Z' || second !in 'A'..'Z') return "🌐"
            val offset = 0x1F1E6 - 'A'.code
            val c1 = String(Character.toChars(first.code + offset))
            val c2 = String(Character.toChars(second.code + offset))
            return "$c1$c2"
        }
    }
}

enum class PingTier {
    UNTESTED,
    FAST,
    MEDIUM,
    SLOW,
    UNREACHABLE
}
