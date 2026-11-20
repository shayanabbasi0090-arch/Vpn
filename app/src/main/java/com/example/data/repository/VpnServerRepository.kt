package com.example.data.repository

import com.example.data.local.SettingsRepository
import com.example.data.probe.ServerPingProber
import com.example.data.remote.PublicVpnApiClient
import com.example.domain.model.ServerProtocol
import com.example.domain.model.VpnServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

data class DirectoryRefreshInfo(
    val isLoading: Boolean = false,
    val isProbingPing: Boolean = false,
    val usedFallback: Boolean = false,
    val lastUpdatedMillis: Long = 0L,
    val errorBanner: String? = null
)

class VpnServerRepository(
    private val publicVpnApiClient: PublicVpnApiClient,
    private val pingProber: ServerPingProber,
    private val settingsRepository: SettingsRepository
) {
    private val _rawServers = MutableStateFlow<List<VpnServer>>(publicVpnApiClient.defaultWireGuardEdgeNodes())
    private val _refreshInfo = MutableStateFlow(DirectoryRefreshInfo())
    val refreshInfo: StateFlow<DirectoryRefreshInfo> = _refreshInfo.asStateFlow()

    val serversFlow: Flow<List<VpnServer>> = combine(
        _rawServers,
        settingsRepository.favoriteIdsFlow,
        settingsRepository.settingsFlow
    ) { raw, favorites, settings ->
        val withCustom = buildList {
            val customConf = settings.customWireGuardConfig.trim()
            if (customConf.contains("[Interface]") && customConf.contains("[Peer]")) {
                val endpointLine = customConf.lineSequence()
                    .firstOrNull { it.trim().startsWith("Endpoint", ignoreCase = true) }
                    ?.substringAfter("=")?.trim().orEmpty()
                val host = endpointLine.substringBefore(":").ifBlank { "127.0.0.1" }
                val port = endpointLine.substringAfter(":", "51820").toIntOrNull() ?: 51820
                add(
                    VpnServer(
                        id = "custom_wg_profile",
                        name = "Custom WireGuard Profile",
                        countryCode = "CH",
                        countryName = "Custom Endpoint",
                        city = host,
                        host = host,
                        port = port,
                        protocol = ServerProtocol.WIREGUARD,
                        pingMs = raw.firstOrNull { it.id == "custom_wg_profile" }?.pingMs,
                        bandwidthMbps = 1000.0,
                        loadPercent = 5,
                        score = 10_000_000L,
                        source = "Custom WG",
                        wireGuardConfig = customConf,
                        isFavorite = "custom_wg_profile" in favorites
                    )
                )
            }
            addAll(raw.map { it.copy(isFavorite = it.id in favorites) })
        }

        sortBySpeedAndHealth(withCustom)
    }

    suspend fun loadInitialAndRefresh() {
        val cached = settingsRepository.loadCachedServers()
        if (cached.isNotEmpty()) {
            _rawServers.value = cached
        }
        refreshServersAndPing()
    }

    suspend fun refreshServersAndPing() {
        _refreshInfo.value = _refreshInfo.value.copy(
            isLoading = true,
            errorBanner = null
        )

        val fetchResult = runCatching { publicVpnApiClient.fetchPublicServers() }
            .getOrElse { err ->
                _refreshInfo.value = _refreshInfo.value.copy(
                    isLoading = false,
                    usedFallback = true,
                    errorBanner = "Live server feed unavailable (${err.message}). Using cached/edge servers."
                )
                return
            }

        _rawServers.value = fetchResult.servers
        _refreshInfo.value = _refreshInfo.value.copy(
            isLoading = false,
            isProbingPing = true,
            usedFallback = fetchResult.usedFallback,
            lastUpdatedMillis = System.currentTimeMillis()
        )

        val pinged = pingProber.pingAll(fetchResult.servers)
        val sorted = sortBySpeedAndHealth(pinged)
        _rawServers.value = sorted
        settingsRepository.saveCachedServers(sorted)

        _refreshInfo.value = _refreshInfo.value.copy(
            isProbingPing = false,
            lastUpdatedMillis = System.currentTimeMillis(),
            errorBanner = if (fetchResult.usedFallback) {
                "Public VPNGate feed unreachable on current network; using verified global edge servers."
            } else null
        )
    }

    suspend fun measureSingleServerPing(server: VpnServer): Int {
        val rtt = pingProber.pingSingle(server)
        _rawServers.value = _rawServers.value.map {
            if (it.id == server.id) it.copy(pingMs = rtt) else it
        }
        return rtt
    }

    suspend fun getBestCandidate(
        excludeIds: Set<String> = emptySet(),
        preferWireGuard: Boolean = true
    ): VpnServer? {
        val list = serversFlow.first().filter { it.id !in excludeIds && !it.isSmartAuto }
        val healthy = list.filter { (it.pingMs ?: Int.MAX_VALUE) > 0 }
        if (healthy.isNotEmpty()) {
            if (preferWireGuard) {
                val fastWg = healthy.firstOrNull {
                    it.protocol == ServerProtocol.WIREGUARD && (it.pingMs ?: 999) <= 180
                }
                if (fastWg != null) return fastWg
            }
            return healthy.minByOrNull { it.pingMs ?: Int.MAX_VALUE }
        }
        return list.firstOrNull()
    }

    private fun sortBySpeedAndHealth(servers: List<VpnServer>): List<VpnServer> {
        return servers.sortedWith(
            compareBy<VpnServer>(
                {
                    when {
                        it.pingMs == null -> 2
                        it.pingMs < 0 -> 3
                        else -> 0
                    }
                },
                { if ((it.pingMs ?: 0) > 0) it.pingMs else Int.MAX_VALUE },
                { -it.bandwidthMbps },
                { -it.score }
            )
        )
    }
}
