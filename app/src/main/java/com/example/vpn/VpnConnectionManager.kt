package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Process
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.SettingsRepository
import com.example.data.remote.WarpApiClient
import com.example.data.repository.VpnServerRepository
import com.example.domain.model.ServerProtocol
import com.example.domain.model.TunnelEngineType
import com.example.domain.model.TunnelStats
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnServer
import com.example.vpn.wg.ParsedWireGuardConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class DiagnosticState {
    data object Idle : DiagnosticState()
    data object Running : DiagnosticState()
    data class Completed(val result: TunnelDiagnosticResult) : DiagnosticState()
}

class VpnConnectionManager(
    private val context: Context,
    private val serverRepository: VpnServerRepository,
    private val settingsRepository: SettingsRepository,
    private val warpApiClient: WarpApiClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val diagnosticsTester = TunnelDiagnosticsTester()

    private val _connectionState = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected())
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _tunnelStats = MutableStateFlow(TunnelStats())
    val tunnelStats: StateFlow<TunnelStats> = _tunnelStats.asStateFlow()

    private val _diagnosticState = MutableStateFlow<DiagnosticState>(DiagnosticState.Idle)
    val diagnosticState: StateFlow<DiagnosticState> = _diagnosticState.asStateFlow()

    private var tickerJob: Job? = null
    private var watchdogJob: Job? = null
    private var activeServer: VpnServer? = null
    private var connectedAtMillis: Long = 0L
    private var autoSwitchCounter: Int = 0
    private var failedServerIds = mutableSetOf<String>()

    init {
        scope.launch {
            SentinelVpnService.revokedEvent.collect { timestamp ->
                if (timestamp > 0L && _connectionState.value is VpnConnectionState.Connected) {
                    disconnect("VPN permission revoked by system")
                }
            }
        }
    }

    fun hasVpnPermission(): Boolean = VpnService.prepare(context) == null

    fun prepareVpnIntent(): Intent? = VpnService.prepare(context)

    fun connect(requestedServer: VpnServer) {
        scope.launch {
            connectInternal(requestedServer, isFailover = false)
        }
    }

    fun runDiagnostics() {
        val current = activeServer ?: return
        if (_diagnosticState.value is DiagnosticState.Running) return
        scope.launch {
            _diagnosticState.value = DiagnosticState.Running
            val result = diagnosticsTester.runDiagnostics(current)
            _diagnosticState.value = DiagnosticState.Completed(result)
        }
    }

    fun fastSwitchNow(reason: String = "Optimizing route to lowest-latency node") {
        scope.launch {
            val current = activeServer
            val exclude = buildSet {
                current?.id?.let { add(it) }
                addAll(failedServerIds)
            }
            val settings = settingsRepository.settingsFlow.first()
            val candidate = serverRepository.getBestCandidate(
                excludeIds = exclude,
                preferWireGuard = settings.preferWireGuardEngine
            ) ?: serverRepository.getBestCandidate(
                excludeIds = setOfNotNull(current?.id),
                preferWireGuard = settings.preferWireGuardEngine
            )

            if (candidate == null) {
                _connectionState.value = VpnConnectionState.Error(
                    message = "No healthy alternative server found for fast-switch.",
                    lastServer = current
                )
                return@launch
            }

            autoSwitchCounter += 1
            _connectionState.value = VpnConnectionState.Reconnecting(
                fromServer = current,
                toServer = candidate,
                reason = reason
            )
            tearDownCurrentTunnel(resetStats = false)
            delay(350)
            connectInternal(candidate, isFailover = true)
        }
    }

    fun disconnect(reason: String? = null) {
        scope.launch {
            tearDownCurrentTunnel(resetStats = true)
            activeServer = null
            failedServerIds.clear()
            _diagnosticState.value = DiagnosticState.Idle
            _connectionState.value = VpnConnectionState.Disconnected(lastReason = reason)
        }
    }

    private suspend fun connectInternal(requestedServer: VpnServer, isFailover: Boolean) {
        if (!hasVpnPermission()) {
            _connectionState.value = VpnConnectionState.Error(
                message = "VPN permission is required before starting tunnel.",
                lastServer = requestedServer
            )
            return
        }

        tearDownCurrentTunnel(resetStats = !isFailover)
        _diagnosticState.value = DiagnosticState.Idle

        val settings = settingsRepository.settingsFlow.first()
        val resolvedServer = if (requestedServer.isSmartAuto) {
            _connectionState.value = VpnConnectionState.Connecting(
                server = requestedServer,
                stepLabel = "Measuring fastest server…"
            )
            serverRepository.getBestCandidate(
                excludeIds = failedServerIds,
                preferWireGuard = settings.preferWireGuardEngine
            ) ?: requestedServer
        } else {
            requestedServer
        }

        _connectionState.value = VpnConnectionState.Connecting(
            server = resolvedServer,
            stepLabel = "Verifying ${resolvedServer.countryName} latency…"
        )
        val livePing = serverRepository.measureSingleServerPing(resolvedServer)
        if (livePing < 0 && settings.autoFastSwitchEnabled && !isFailover) {
            failedServerIds.add(resolvedServer.id)
            val fallback = serverRepository.getBestCandidate(
                excludeIds = failedServerIds,
                preferWireGuard = settings.preferWireGuardEngine
            )
            if (fallback != null && fallback.id != resolvedServer.id) {
                autoSwitchCounter += 1
                _connectionState.value = VpnConnectionState.Reconnecting(
                    fromServer = resolvedServer,
                    toServer = fallback,
                    reason = "${resolvedServer.city} unreachable; auto-switching to ${fallback.countryName}"
                )
                delay(300)
                connectInternal(fallback, isFailover = true)
                return
            }
        }

        var activeWgConfig: String? = null
        var socksHost: String? = null
        var socksPort = 0
        var assignedIp = "10.10.0.2/32"
        var engineType = TunnelEngineType.ANDROID_VPN_TUN

        when (resolvedServer.protocol) {
            ServerProtocol.WIREGUARD -> {
                if (settings.preferWireGuardEngine || resolvedServer.wireGuardConfig != null) {
                    _connectionState.value = VpnConnectionState.Connecting(
                        server = resolvedServer,
                        stepLabel = "Generating X25519 Noise-IK keys…"
                    )

                    val customWg = resolvedServer.wireGuardConfig
                    if (!customWg.isNullOrBlank()) {
                        val parsed = ParsedWireGuardConfig.parse(customWg).getOrNull()
                        if (parsed != null) {
                            activeWgConfig = customWg
                            assignedIp = parsed.addressCidr
                            engineType = TunnelEngineType.WIREGUARD_NOISE
                        }
                    } else {
                        val warpProvision = warpApiClient.registerWarpTunnel(
                            dnsPrimaryIp = settings.dnsProvider.primaryIp,
                            preferredEndpointHostPort = "${resolvedServer.host}:${resolvedServer.port}"
                        )
                        if (warpProvision.isSuccess) {
                            val cfg = warpProvision.getOrThrow()
                            activeWgConfig = cfg.configText
                            assignedIp = cfg.assignedIpv4
                            engineType = TunnelEngineType.WIREGUARD_NOISE
                        }
                    }
                }
            }

            ServerProtocol.SOCKS5_TUN -> {
                socksHost = resolvedServer.host
                socksPort = resolvedServer.port
                assignedIp = "${resolvedServer.host} (TUN)"
                engineType = TunnelEngineType.SOCKS5_TUNNEL
            }

            ServerProtocol.RELAY_TUN -> {
                engineType = TunnelEngineType.ANDROID_VPN_TUN
            }
        }

        startSentinelTunService(
            server = resolvedServer,
            dns1 = settings.dnsProvider.primaryIp,
            dns2 = settings.dnsProvider.secondaryIp,
            excludeLan = settings.splitTunnelExcludeLan,
            wgConfigText = activeWgConfig,
            socksHost = socksHost,
            socksPort = socksPort
        )

        onTunnelEstablished(
            server = resolvedServer,
            engineType = engineType,
            assignedIp = assignedIp,
            livePing = livePing.takeIf { it > 0 }
        )
    }

    private fun startSentinelTunService(
        server: VpnServer,
        dns1: String,
        dns2: String,
        excludeLan: Boolean,
        wgConfigText: String?,
        socksHost: String?,
        socksPort: Int
    ) {
        val intent = Intent(context, SentinelVpnService::class.java).apply {
            action = SentinelVpnService.ACTION_START
            putExtra(SentinelVpnService.EXTRA_SERVER_NAME, "${server.countryName} (${server.city})")
            putExtra(SentinelVpnService.EXTRA_DNS_PRIMARY, dns1)
            putExtra(SentinelVpnService.EXTRA_DNS_SECONDARY, dns2)
            putExtra(SentinelVpnService.EXTRA_EXCLUDE_LAN, excludeLan)
            if (!wgConfigText.isNullOrBlank()) {
                putExtra(SentinelVpnService.EXTRA_WG_CONFIG, wgConfigText)
            }
            if (!socksHost.isNullOrBlank() && socksPort in 1..65535) {
                putExtra(SentinelVpnService.EXTRA_SOCKS_HOST, socksHost)
                putExtra(SentinelVpnService.EXTRA_SOCKS_PORT, socksPort)
            }
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun onTunnelEstablished(
        server: VpnServer,
        engineType: TunnelEngineType,
        assignedIp: String,
        livePing: Int?
    ) {
        activeServer = server
        connectedAtMillis = System.currentTimeMillis()

        _connectionState.value = VpnConnectionState.Connected(
            server = server,
            engineType = engineType,
            assignedIp = assignedIp,
            connectedAtMillis = connectedAtMillis
        )

        _tunnelStats.value = _tunnelStats.value.copy(
            livePingMs = livePing ?: server.pingMs,
            autoSwitchCount = autoSwitchCounter
        )

        startTelemetryAndWatchdog(server, engineType)
    }

    private fun startTelemetryAndWatchdog(server: VpnServer, engineType: TunnelEngineType) {
        tickerJob?.cancel()
        watchdogJob?.cancel()

        val uid = Process.myUid()
        var prevRx = SentinelVpnService.rxBytes.get()
        var prevTx = SentinelVpnService.txBytes.get()
        if (prevRx == 0L && prevTx == 0L) {
            prevRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L) - TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            prevTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L) - TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        }

        tickerJob = scope.launch {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val durationSec = ((now - connectedAtMillis) / 1000L).coerceAtLeast(0L)

                val rawRx = SentinelVpnService.rxBytes.get()
                val rawTx = SentinelVpnService.txBytes.get()
                val currentRx: Long
                val currentTx: Long
                if (rawRx > 0L || rawTx > 0L) {
                    currentRx = rawRx
                    currentTx = rawTx
                } else {
                    currentRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
                    currentTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
                }

                val dlRate = (currentRx - prevRx).coerceAtLeast(0L)
                val ulRate = (currentTx - prevTx).coerceAtLeast(0L)
                prevRx = currentRx
                prevTx = currentTx

                val updatedStats = _tunnelStats.value.copy(
                    durationSeconds = durationSec,
                    downloadBytesPerSec = dlRate,
                    uploadBytesPerSec = ulRate,
                    totalDownloadBytes = if (rawRx > 0L) rawRx else _tunnelStats.value.totalDownloadBytes + dlRate,
                    totalUploadBytes = if (rawTx > 0L) rawTx else _tunnelStats.value.totalUploadBytes + ulRate,
                    autoSwitchCount = autoSwitchCounter
                )
                _tunnelStats.value = updatedStats

                val speedLine =
                    "${server.flagEmoji} ${server.countryName} • ${updatedStats.formattedDuration()} • ↓ ${
                        TunnelStats.formatSpeed(dlRate)
                    } ↑ ${TunnelStats.formatSpeed(ulRate)}"
                VpnNotificationHelper.updateNotification(
                    context = context,
                    title = context.getString(R.string.vpn_notif_title_connected),
                    contentText = speedLine,
                    subText = engineType.label
                )
            }
        }

        watchdogJob = scope.launch {
            var consecutiveTimeouts = 0
            while (isActive) {
                delay(6000)
                val settings = settingsRepository.settingsFlow.first()
                val rtt = serverRepository.measureSingleServerPing(server)
                if (rtt < 0) {
                    consecutiveTimeouts += 1
                    _tunnelStats.value = _tunnelStats.value.copy(
                        packetLossPercent = (consecutiveTimeouts * 50).coerceAtMost(100)
                    )
                } else {
                    consecutiveTimeouts = 0
                    _tunnelStats.value = _tunnelStats.value.copy(
                        livePingMs = rtt,
                        packetLossPercent = 0
                    )
                }

                val isHighLatency = rtt > settings.latencyThresholdMs
                val isTimedOut = consecutiveTimeouts >= 2

                if (settings.autoFastSwitchEnabled && (isTimedOut || isHighLatency)) {
                    failedServerIds.add(server.id)
                    val switchReason = when {
                        isTimedOut -> "${server.city} timed out; auto-switching"
                        else -> "High latency (${rtt}ms > ${settings.latencyThresholdMs}ms); switching"
                    }
                    fastSwitchNow(switchReason)
                    break
                }
            }
        }
    }

    private suspend fun tearDownCurrentTunnel(resetStats: Boolean) {
        tickerJob?.cancel()
        tickerJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        runCatching {
            val stopTunIntent = Intent(context, SentinelVpnService::class.java).apply {
                action = SentinelVpnService.ACTION_STOP
            }
            context.startService(stopTunIntent)
        }
        VpnNotificationHelper.cancelNotification(context)

        if (resetStats) {
            autoSwitchCounter = 0
            _tunnelStats.value = TunnelStats()
        }
    }
}
