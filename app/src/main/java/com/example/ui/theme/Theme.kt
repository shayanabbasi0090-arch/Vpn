package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SentinelDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = CyberVoid,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = NeonEmerald,
    onSecondary = CyberVoid,
    secondaryContainer = NeonEmeraldContainer,
    onSecondaryContainer = NeonEmerald,
    tertiary = WarningAmber,
    onTertiary = CyberVoid,
    tertiaryContainer = WarningAmberContainer,
    onTertiaryContainer = WarningAmber,
    error = AlertCoral,
    onError = CyberVoid,
    errorContainer = AlertCoralContainer,
    onErrorContainer = AlertCoral,
    background = CyberVoid,
    onBackground = TextPrimary,
    surface = CyberSlateDeep,
    onSurface = TextPrimary,
    surfaceVariant = CyberSurface,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLow = CyberSlateDeep,
    surfaceContainer = CyberSurface,
    surfaceContainerHigh = CyberSurfaceElevated,
    outline = CyberBorder,
    outlineVariant = CyberBorder.copy(alpha = 0.55f)
)

val SentinelShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun SentinelVpnTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SentinelDarkColorScheme,
        typography = SentinelTypography,
        shapes = SentinelShapes,
        content = content
    )
}
