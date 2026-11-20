package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.DnsProvider
import com.example.ui.VpnUiState
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberSlateDeep
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberVoid
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    uiState: VpnUiState,
    hasVpnPermission: Boolean,
    onRequestVpnPermission: () -> Unit,
    onAutoFastSwitchChange: (Boolean) -> Unit,
    onLatencyThresholdChange: (Int) -> Unit,
    onPreferWireGuardChange: (Boolean) -> Unit,
    onDnsProviderChange: (DnsProvider) -> Unit,
    onExcludeLanChange: (Boolean) -> Unit,
    onSaveCustomWireGuardConfig: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings
    var wgDraft by remember(settings.customWireGuardConfig) {
        mutableStateOf(settings.customWireGuardConfig)
    }
    var savedBanner by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Tunnel & Failover",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )

            // Auto Fast-Switch Card
            Card(
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
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = null,
                                tint = ElectricCyan
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Auto Fast-Switch Failover",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Automatically switch servers on timeout or high ping",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = settings.autoFastSwitchEnabled,
                            onCheckedChange = onAutoFastSwitchChange,
                            modifier = Modifier.testTag("auto_switch_toggle"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberVoid,
                                checkedTrackColor = NeonEmerald
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Latency Failover Threshold: ${settings.latencyThresholdMs} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                    Slider(
                        value = settings.latencyThresholdMs.toFloat(),
                        onValueChange = { onLatencyThresholdChange(it.toInt()) },
                        valueRange = 100f..350f,
                        steps = 4,
                        enabled = settings.autoFastSwitchEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricCyan,
                            activeTrackColor = ElectricCyan
                        )
                    )
                }
            }

            // Protocol & DNS Card
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                border = BorderStroke(1.dp, CyberBorder),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = NeonEmerald
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Prefer WireGuard Noise-IK",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Use Curve25519 WireGuard tunnel when available",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = settings.preferWireGuardEngine,
                            onCheckedChange = onPreferWireGuardChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberVoid,
                                checkedTrackColor = ElectricCyan
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bypass Local LAN Subnets",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "Keep local printers & cast devices reachable",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.splitTunnelExcludeLan,
                            onCheckedChange = onExcludeLanChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberVoid,
                                checkedTrackColor = ElectricCyan
                            )
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = ElectricCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tunnel DNS Resolver",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DnsProvider.entries.forEach { dns ->
                            val selected = dns == settings.dnsProvider
                            FilterChip(
                                selected = selected,
                                onClick = { onDnsProviderChange(dns) },
                                label = {
                                    Text(
                                        text = dns.primaryIp,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = CyberSlateDeep,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = ElectricCyanContainer,
                                    selectedLabelColor = ElectricCyan
                                )
                            )
                        }
                    }
                }
            }

            // Custom WireGuard Config Import
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                border = BorderStroke(1.dp, CyberBorder),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Custom WireGuard (.conf) Profile",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Paste any [Interface] / [Peer] WireGuard config to add it as a dedicated high-speed node.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = wgDraft,
                        onValueChange = {
                            wgDraft = it
                            savedBanner = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .testTag("custom_wg_input"),
                        placeholder = {
                            Text(
                                text = "[Interface]\nPrivateKey = ...\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = ...\nEndpoint = host:51820",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        },
                        textStyle = MaterialTheme.typography.labelSmall.copy(color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = CyberSlateDeep,
                            unfocusedContainerColor = CyberSlateDeep,
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = CyberBorder
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = savedBanner ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonEmerald,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                onSaveCustomWireGuardConfig(wgDraft.trim())
                                savedBanner = if (wgDraft.isBlank()) {
                                    "Custom profile cleared"
                                } else {
                                    "Custom WireGuard node saved"
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("save_wg_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = CyberVoid
                            )
                        ) {
                            Text("Save Config")
                        }
                    }
                }
            }

            // VPN Permission & Diagnostics Card
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSlateDeep),
                border = BorderStroke(1.dp, CyberBorder),
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = if (hasVpnPermission) NeonEmerald else ElectricCyan
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Android VpnService Permission",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = if (hasVpnPermission) {
                                    "Authorized • Status bar key icon active during tunnel"
                                } else {
                                    "Authorization prompt will appear on first connect"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    if (!hasVpnPermission) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onRequestVpnPermission,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = CyberVoid
                            )
                        ) {
                            Text("Grant")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
