package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.VpnConnectionState
import com.example.ui.components.CyberGridBackdrop
import com.example.ui.screens.ServersDirectoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ShieldDashboardScreen
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberSlateDeep
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

enum class SentinelTab(
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    SHIELD(R.string.cd_shield_nav, Icons.Rounded.Shield, Icons.Outlined.Shield, "nav_shield"),
    SERVERS(R.string.cd_servers_nav, Icons.Rounded.Public, Icons.Outlined.Public, "nav_servers"),
    SETTINGS(R.string.cd_settings_nav, Icons.Rounded.Settings, Icons.Outlined.Settings, "nav_settings")
}

@Composable
fun SentinelApp(viewModel: VpnViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var currentTab by remember { mutableStateOf(SentinelTab.SHIELD) }
    var hasVpnPermission by remember {
        mutableStateOf(viewModel.prepareVpnPermissionIntent() == null)
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        hasVpnPermission = viewModel.prepareVpnPermissionIntent() == null
        viewModel.onVpnPermissionResult(granted)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (currentTab != SentinelTab.SHIELD) {
        BackHandler {
            currentTab = SentinelTab.SHIELD
        }
    }

    val launchVpnPermission: (Intent) -> Unit = { intent ->
        vpnPermissionLauncher.launch(intent)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp
        val isConnected = uiState.connectionState is VpnConnectionState.Connected

        Box(modifier = Modifier.fillMaxSize()) {
            CyberGridBackdrop(isConnected = isConnected)

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!useNavRail) {
                        NavigationBar(
                            containerColor = CyberSlateDeep.copy(alpha = 0.96f),
                            tonalElevation = 0.dp
                        ) {
                            SentinelTab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                val label = when (tab) {
                                    SentinelTab.SHIELD -> "Shield"
                                    SentinelTab.SERVERS -> "Servers"
                                    SentinelTab.SETTINGS -> "Settings"
                                }
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { currentTab = tab },
                                    modifier = Modifier.testTag(tab.tag),
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.titleRes)
                                        )
                                    },
                                    label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = ElectricCyan,
                                        selectedTextColor = ElectricCyan,
                                        indicatorColor = ElectricCyanContainer,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavRail) {
                        NavigationRail(
                            containerColor = CyberSlateDeep.copy(alpha = 0.96f),
                            contentColor = TextPrimary
                        ) {
                            SentinelTab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                val label = when (tab) {
                                    SentinelTab.SHIELD -> "Shield"
                                    SentinelTab.SERVERS -> "Servers"
                                    SentinelTab.SETTINGS -> "Settings"
                                }
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { currentTab = tab },
                                    modifier = Modifier.testTag(tab.tag),
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.titleRes)
                                        )
                                    },
                                    label = { Text(label) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = ElectricCyan,
                                        selectedTextColor = ElectricCyan,
                                        indicatorColor = ElectricCyanContainer,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        when (currentTab) {
                            SentinelTab.SHIELD -> ShieldDashboardScreen(
                                uiState = uiState,
                                onToggleConnect = {
                                    viewModel.togglePrimaryConnection(launchVpnPermission)
                                },
                                onFastSwitch = { viewModel.fastSwitchNow() },
                                onRunDiagnostics = { viewModel.runDiagnostics() },
                                onOpenServers = { currentTab = SentinelTab.SERVERS }
                            )

                            SentinelTab.SERVERS -> ServersDirectoryScreen(
                                uiState = uiState,
                                onSearchChange = viewModel::updateSearchQuery,
                                onFilterChange = viewModel::updateFilter,
                                onRefreshServers = viewModel::refreshServers,
                                onConnectServer = { server ->
                                    viewModel.connectToServer(server, launchVpnPermission)
                                    currentTab = SentinelTab.SHIELD
                                },
                                onToggleFavorite = viewModel::toggleFavorite
                            )

                            SentinelTab.SETTINGS -> SettingsScreen(
                                uiState = uiState,
                                hasVpnPermission = hasVpnPermission,
                                onRequestVpnPermission = {
                                    viewModel.prepareVpnPermissionIntent()?.let(launchVpnPermission)
                                },
                                onAutoFastSwitchChange = viewModel::setAutoFastSwitch,
                                onLatencyThresholdChange = viewModel::setLatencyThreshold,
                                onPreferWireGuardChange = viewModel::setPreferWireGuard,
                                onDnsProviderChange = viewModel::setDnsProvider,
                                onExcludeLanChange = viewModel::setExcludeLan,
                                onSaveCustomWireGuardConfig = viewModel::saveCustomWireGuardConfig
                            )
                        }
                    }
                }
            }
        }
    }
}
