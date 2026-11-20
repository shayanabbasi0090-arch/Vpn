package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.model.TunnelStats
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnServer
import com.example.ui.VpnUiState
import com.example.vpn.DiagnosticState
import com.example.ui.components.ConnectionStatusPill
import com.example.ui.components.PingBadge
import com.example.ui.components.SentinelShieldMark
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberSlateDeep
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberVoid
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.NeonEmeraldContainer
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun ShieldDashboardScreen(
    uiState: VpnUiState,
    onToggleConnect: () -> Unit,
    onFastSwitch: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onOpenServers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val state = uiState.connectionState
    val stats = uiState.tunnelStats

    val displayedServer: VpnServer = when (state) {
        is VpnConnectionState.Connected -> state.server
        is VpnConnectionState.Connecting -> state.server
        is VpnConnectionState.Reconnecting -> state.toServer
        else -> uiState.selectedServer
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Brand Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SentinelShieldMark(
                        size = 38.dp,
                        isActive = state is VpnConnectionState.Connected
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "SENTINEL VPN",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = if (uiState.settings.autoFastSwitchEnabled) {
                                "Auto-Failover Armed"
                            } else {
                                "Manual Route Mode"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                ConnectionStatusPill(state = state)
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Hero Animated Shield Power Button
            HeroShieldButton(
                connectionState = state,
                stats = stats,
                onClick = onToggleConnect
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Status Subtitle / Assigned IP / Protocol Pill
            AnimatedContent(targetState = state, label = "stateBanner") { currentState ->
                when (currentState) {
                    is VpnConnectionState.Connected -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stats.formattedDuration(),
                                style = MaterialTheme.typography.displaySmall,
                                color = NeonEmerald,
                                modifier = Modifier.testTag("connection_timer")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VerifiedUser,
                                    contentDescription = null,
                                    tint = NeonEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${currentState.engineType.label} • IP ${currentState.assignedIp}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    is VpnConnectionState.Connecting -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Establishing Encrypted Tunnel",
                                style = MaterialTheme.typography.titleMedium,
                                color = ElectricCyan
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = currentState.stepLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }

                    is VpnConnectionState.Reconnecting -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Auto-Switching Server…",
                                style = MaterialTheme.typography.titleMedium,
                                color = WarningAmber
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = currentState.reason,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }

                    is VpnConnectionState.Error -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Connection Interrupted",
                                style = MaterialTheme.typography.titleMedium,
                                color = AlertCoral
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = currentState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }

                    is VpnConnectionState.Disconnected -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Tap Shield to Encrypt Traffic",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = currentState.lastReason
                                    ?: "Ready for WireGuard & VpnService protection",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Live Upload / Download Speed Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SpeedStatCard(
                    title = "DOWNLOAD",
                    speedText = TunnelStats.formatSpeed(stats.downloadBytesPerSec),
                    totalText = "Total ${TunnelStats.formatBytes(stats.totalDownloadBytes)}",
                    accentColor = ElectricCyan,
                    icon = Icons.Rounded.ArrowDownward,
                    modifier = Modifier.weight(1f)
                )
                SpeedStatCard(
                    title = "UPLOAD",
                    speedText = TunnelStats.formatSpeed(stats.uploadBytesPerSec),
                    totalText = "Total ${TunnelStats.formatBytes(stats.totalUploadBytes)}",
                    accentColor = NeonEmerald,
                    icon = Icons.Rounded.ArrowUpward,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Active / Selected Location Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("selected_server_card")
                    .clickable(onClick = onOpenServers),
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                border = BorderStroke(1.dp, CyberBorder),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CyberSurfaceElevated)
                                    .border(1.dp, CyberBorder, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = displayedServer.flagEmoji,
                                    fontSize = 24.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (displayedServer.isSmartAuto) {
                                            "Smart Auto-Connect"
                                        } else {
                                            displayedServer.countryName
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (displayedServer.isSmartAuto) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.Rounded.Bolt,
                                            contentDescription = null,
                                            tint = ElectricCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "${displayedServer.city} • ${displayedServer.protocol.displayLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        PingBadge(
                            pingMs = stats.livePingMs ?: displayedServer.pingMs,
                            tier = displayedServer.pingTier
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenServers,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("change_server_button"),
                            border = BorderStroke(1.dp, CyberBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Countries (${uiState.allServers.size})")
                        }

                        FilledTonalButton(
                            onClick = onFastSwitch,
                            enabled = state is VpnConnectionState.Connected,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("fast_switch_button"),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = ElectricCyanContainer,
                                contentColor = ElectricCyan,
                                disabledContainerColor = CyberSlateDeep,
                                disabledContentColor = TextMuted
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Fast Switch")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Exit IP & Live Download Mbps Verification Card (visible when connected)
            if (state is VpnConnectionState.Connected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CyberSurface),
                    border = BorderStroke(1.dp, CyberBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TUNNEL EXIT IP & SPEED",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            when (val diag = uiState.diagnosticState) {
                                DiagnosticState.Idle -> Text(
                                    text = "Tap Verify to test real remote IP & Mbps",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                DiagnosticState.Running -> Text(
                                    text = "Measuring exit IP & throughput…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ElectricCyan
                                )
                                is DiagnosticState.Completed -> {
                                    val r = diag.result
                                    if (r.errorMessage != null) {
                                        Text(
                                            text = r.errorMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AlertCoral,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    } else {
                                        Text(
                                            text = "${r.exitIp} (${r.exitCountry}) • ${String.format("%.2f", r.downloadMbps)} Mbps",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = NeonEmerald
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        FilledTonalButton(
                            onClick = onRunDiagnostics,
                            enabled = uiState.diagnosticState !is DiagnosticState.Running,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("speed_test_button"),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = ElectricCyanContainer,
                                contentColor = ElectricCyan
                            )
                        ) {
                            if (uiState.diagnosticState is DiagnosticState.Running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = ElectricCyan
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Verify")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Security & Health Telemetry Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CyberSlateDeep,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, CyberBorder.copy(alpha = 0.65f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TelemetryItem(
                        label = "DNS",
                        value = uiState.settings.dnsProvider.primaryIp
                    )
                    TelemetryItem(
                        label = "FAILOVER",
                        value = if (uiState.settings.autoFastSwitchEnabled) {
                            "<${uiState.settings.latencyThresholdMs}ms"
                        } else {
                            "OFF"
                        }
                    )
                    TelemetryItem(
                        label = "SWITCHES",
                        value = "${stats.autoSwitchCount}"
                    )
                    TelemetryItem(
                        label = "LOSS",
                        value = "${stats.packetLossPercent}%"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HeroShieldButton(
    connectionState: VpnConnectionState,
    stats: TunnelStats,
    onClick: () -> Unit
) {
    val isConnected = connectionState is VpnConnectionState.Connected
    val isConnecting = connectionState is VpnConnectionState.Connecting ||
        connectionState is VpnConnectionState.Reconnecting

    val primaryRingColor by animateColorAsState(
        targetValue = when (connectionState) {
            is VpnConnectionState.Connected -> NeonEmerald
            is VpnConnectionState.Reconnecting -> WarningAmber
            is VpnConnectionState.Error -> AlertCoral
            is VpnConnectionState.Connecting, is VpnConnectionState.Disconnected -> ElectricCyan
        },
        animationSpec = tween(450),
        label = "ringColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "radarAnim")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isConnecting) 1200 else 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseRadius"
    )

    val cdConnect = stringResource(R.string.cd_connect_button)

    Box(
        modifier = Modifier
            .size(210.dp)
            .clip(CircleShape)
            .testTag("connect_button")
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f

            // Outer ambient pulse ring
            drawCircle(
                color = primaryRingColor.copy(alpha = if (isConnected || isConnecting) 0.16f else 0.08f),
                radius = maxRadius * pulseRadius,
                center = center
            )

            // Outer track ring
            drawCircle(
                color = primaryRingColor.copy(alpha = 0.25f),
                radius = maxRadius * 0.86f,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )

            // Rotating radar arc
            rotate(degrees = sweepAngle, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            primaryRingColor.copy(alpha = 0.85f),
                            Color.Transparent
                        )
                    ),
                    startAngle = 0f,
                    sweepAngle = if (isConnecting) 160f else 110f,
                    useCenter = false,
                    topLeft = Offset(center.x - maxRadius * 0.86f, center.y - maxRadius * 0.86f),
                    size = androidx.compose.ui.geometry.Size(maxRadius * 1.72f, maxRadius * 1.72f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // Inner circular power disc
        Box(
            modifier = Modifier
                .size(144.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (isConnected) NeonEmeraldContainer else CyberSurfaceElevated,
                            CyberSlateDeep
                        )
                    )
                )
                .border(
                    width = 2.dp,
                    color = primaryRingColor.copy(alpha = if (isConnected) 0.85f else 0.45f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when {
                        isConnected -> Icons.Rounded.Lock
                        isConnecting -> Icons.Rounded.Shield
                        else -> Icons.Rounded.PowerSettingsNew
                    },
                    contentDescription = cdConnect,
                    tint = primaryRingColor,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (connectionState) {
                        is VpnConnectionState.Connected -> "DISCONNECT"
                        is VpnConnectionState.Connecting -> "CONNECTING"
                        is VpnConnectionState.Reconnecting -> "SWITCHING"
                        else -> "CONNECT"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary
                )
                if (isConnected && stats.livePingMs != null && stats.livePingMs > 0) {
                    Text(
                        text = "${stats.livePingMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonEmerald
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedStatCard(
    title: String,
    speedText: String,
    totalText: String,
    accentColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        border = BorderStroke(1.dp, CyberBorder),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = speedText,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = totalText,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TelemetryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary
        )
    }
}
