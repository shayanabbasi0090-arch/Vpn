package com.example.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.SettingsRepository
import com.example.data.repository.DirectoryRefreshInfo
import com.example.data.repository.VpnServerRepository
import com.example.domain.model.DnsProvider
import com.example.domain.model.ServerProtocol
import com.example.domain.model.TunnelStats
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnServer
import com.example.domain.model.VpnSettings
import com.example.vpn.DiagnosticState
import com.example.vpn.VpnConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ServerFilter(val label: String) {
    ALL("All"),
    FASTEST("Fast <80ms"),
    WIREGUARD("WireGuard"),
    SOCKS5("SOCKS5 IP"),
    FAVORITES("Saved")
}

data class VpnUiState(
    val connectionState: VpnConnectionState = VpnConnectionState.Disconnected(),
    val tunnelStats: TunnelStats = TunnelStats(),
    val allServers: List<VpnServer> = emptyList(),
    val filteredServers: List<VpnServer> = emptyList(),
    val selectedServer: VpnServer = VpnServer.smartAutoPlaceholder(),
    val smartAutoServer: VpnServer = VpnServer.smartAutoPlaceholder(),
    val refreshInfo: DirectoryRefreshInfo = DirectoryRefreshInfo(),
    val settings: VpnSettings = VpnSettings(),
    val searchQuery: String = "",
    val activeFilter: ServerFilter = ServerFilter.ALL,
    val diagnosticState: DiagnosticState = DiagnosticState.Idle
)

class VpnViewModel(
    private val serverRepository: VpnServerRepository,
    private val settingsRepository: SettingsRepository,
    private val connectionManager: VpnConnectionManager
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val filterFlow = MutableStateFlow(ServerFilter.ALL)

    private var pendingConnectServer: VpnServer? = null

    val uiState: StateFlow<VpnUiState> = combine(
        connectionManager.connectionState,
        connectionManager.tunnelStats,
        serverRepository.serversFlow,
        settingsRepository.selectedServerIdFlow,
        serverRepository.refreshInfo,
        settingsRepository.settingsFlow,
        searchQueryFlow,
        filterFlow,
        connectionManager.diagnosticState
    ) { values ->
        val connState = values[0] as VpnConnectionState
        val stats = values[1] as TunnelStats
        @Suppress("UNCHECKED_CAST")
        val servers = values[2] as List<VpnServer>
        val selectedId = values[3] as String
        val refreshInfo = values[4] as DirectoryRefreshInfo
        val settings = values[5] as VpnSettings
        val query = values[6] as String
        val filter = values[7] as ServerFilter
        val diag = values[8] as DiagnosticState

        val fastestHealthy = servers.firstOrNull { (it.pingMs ?: -1) > 0 } ?: servers.firstOrNull()
        val smartAuto = VpnServer.smartAutoPlaceholder(fastestHealthy)

        val selected = if (selectedId == VpnServer.SMART_AUTO_ID) {
            smartAuto
        } else {
            servers.firstOrNull { it.id == selectedId } ?: smartAuto
        }

        val filtered = servers.filter { server ->
            val matchesQuery = query.isBlank() ||
                server.countryName.contains(query, ignoreCase = true) ||
                server.city.contains(query, ignoreCase = true) ||
                server.name.contains(query, ignoreCase = true) ||
                server.countryCode.contains(query, ignoreCase = true)

            val matchesFilter = when (filter) {
                ServerFilter.ALL -> true
                ServerFilter.FASTEST -> (server.pingMs ?: Int.MAX_VALUE) in 1..80
                ServerFilter.WIREGUARD -> server.protocol == ServerProtocol.WIREGUARD
                ServerFilter.SOCKS5 -> server.protocol == ServerProtocol.SOCKS5_TUN
                ServerFilter.FAVORITES -> server.isFavorite
            }
            matchesQuery && matchesFilter
        }

        VpnUiState(
            connectionState = connState,
            tunnelStats = stats,
            allServers = servers,
            filteredServers = filtered,
            selectedServer = selected,
            smartAutoServer = smartAuto,
            refreshInfo = refreshInfo,
            settings = settings,
            searchQuery = query,
            activeFilter = filter,
            diagnosticState = diag
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VpnUiState()
    )

    init {
        viewModelScope.launch {
            serverRepository.loadInitialAndRefresh()
        }
    }

    fun prepareVpnPermissionIntent(): Intent? = connectionManager.prepareVpnIntent()

    fun togglePrimaryConnection(onNeedsPermission: (Intent) -> Unit) {
        when (uiState.value.connectionState) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Connecting,
            is VpnConnectionState.Reconnecting -> {
                connectionManager.disconnect("Disconnected by user")
            }
            is VpnConnectionState.Disconnected,
            is VpnConnectionState.Error -> {
                connectWithPermissionCheck(uiState.value.selectedServer, onNeedsPermission)
            }
        }
    }

    fun connectToServer(server: VpnServer, onNeedsPermission: (Intent) -> Unit) {
        viewModelScope.launch {
            settingsRepository.setSelectedServerId(
                if (server.isSmartAuto) VpnServer.SMART_AUTO_ID else server.id
            )
            connectWithPermissionCheck(server, onNeedsPermission)
        }
    }

    fun selectServerOnly(server: VpnServer) {
        viewModelScope.launch {
            settingsRepository.setSelectedServerId(
                if (server.isSmartAuto) VpnServer.SMART_AUTO_ID else server.id
            )
        }
    }

    private fun connectWithPermissionCheck(
        server: VpnServer,
        onNeedsPermission: (Intent) -> Unit
    ) {
        val intent = connectionManager.prepareVpnIntent()
        if (intent != null) {
            pendingConnectServer = server
            onNeedsPermission(intent)
        } else {
            pendingConnectServer = null
            connectionManager.connect(server)
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        val target = pendingConnectServer ?: uiState.value.selectedServer
        pendingConnectServer = null
        if (granted) {
            connectionManager.connect(target)
        }
    }

    fun fastSwitchNow() {
        connectionManager.fastSwitchNow("Manual fast-switch requested")
    }

    fun runDiagnostics() {
        connectionManager.runDiagnostics()
    }

    fun refreshServers() {
        viewModelScope.launch {
            serverRepository.refreshServersAndPing()
        }
    }

    fun toggleFavorite(serverId: String) {
        viewModelScope.launch {
            settingsRepository.toggleFavorite(serverId)
        }
    }

    fun updateSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun updateFilter(filter: ServerFilter) {
        filterFlow.value = filter
    }

    fun setAutoFastSwitch(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(autoFastSwitchEnabled = enabled) }
        }
    }

    fun setLatencyThreshold(thresholdMs: Int) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(latencyThresholdMs = thresholdMs) }
        }
    }

    fun setPreferWireGuard(prefer: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(preferWireGuardEngine = prefer) }
        }
    }

    fun setDnsProvider(provider: DnsProvider) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(dnsProvider = provider) }
        }
    }

    fun setExcludeLan(exclude: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(splitTunnelExcludeLan = exclude) }
        }
    }

    fun saveCustomWireGuardConfig(config: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(customWireGuardConfig = config) }
        }
    }

    class Factory(
        private val serverRepository: VpnServerRepository,
        private val settingsRepository: SettingsRepository,
        private val connectionManager: VpnConnectionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VpnViewModel(serverRepository, settingsRepository, connectionManager) as T
        }
    }
}
