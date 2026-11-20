package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.PingTier
import com.example.domain.model.VpnConnectionState
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AlertCoralContainer
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberVoid
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.NeonEmeraldContainer
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WarningAmberContainer

@Composable
fun CyberGridBackdrop(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val glowColor by animateColorAsState(
        targetValue = if (isConnected) NeonEmerald.copy(alpha = 0.14f) else ElectricCyan.copy(alpha = 0.11f),
        animationSpec = tween(600),
        label = "backdropGlow"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = CyberVoid)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowColor, Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 0.28f),
                radius = size.maxDimension * 0.55f
            ),
            radius = size.maxDimension * 0.55f,
            center = Offset(size.width * 0.5f, size.height * 0.28f)
        )

        val step = 44.dp.toPx()
        val lineColor = CyberBorder.copy(alpha = 0.18f)
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f
            )
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
            y += step
        }
    }
}

@Composable
fun SentinelShieldMark(
    size: Dp = 36.dp,
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val cd = stringResource(R.string.cd_app_logo)
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = cd }
    ) {
        val w = this.size.width
        val h = this.size.height

        val shieldPath = Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            lineTo(w * 0.12f, h * 0.22f)
            lineTo(w * 0.12f, h * 0.50f)
            cubicTo(w * 0.12f, h * 0.74f, w * 0.28f, h * 0.90f, w * 0.5f, h * 0.96f)
            cubicTo(w * 0.72f, h * 0.90f, w * 0.88f, h * 0.74f, w * 0.88f, h * 0.50f)
            lineTo(w * 0.88f, h * 0.22f)
            close()
        }

        drawPath(
            path = shieldPath,
            brush = Brush.linearGradient(
                colors = if (isActive) listOf(ElectricCyan, NeonEmerald) else listOf(ElectricCyan, CyberBorder)
            )
        )

        val innerPath = Path().apply {
            moveTo(w * 0.5f, h * 0.16f)
            lineTo(w * 0.22f, h * 0.28f)
            lineTo(w * 0.22f, h * 0.50f)
            cubicTo(w * 0.22f, h * 0.68f, w * 0.34f, h * 0.80f, w * 0.5f, h * 0.86f)
            cubicTo(w * 0.66f, h * 0.80f, w * 0.78f, h * 0.68f, w * 0.78f, h * 0.50f)
            lineTo(w * 0.78f, h * 0.28f)
            close()
        }
        drawPath(path = innerPath, color = CyberVoid)

        // Globe ring & signal dot
        drawCircle(
            color = ElectricCyan,
            radius = w * 0.17f,
            center = Offset(w * 0.5f, h * 0.50f),
            style = Stroke(width = w * 0.055f)
        )
        drawCircle(
            color = NeonEmerald,
            radius = w * 0.065f,
            center = Offset(w * 0.5f, h * 0.50f)
        )
    }
}

@Composable
fun ConnectionStatusPill(
    state: VpnConnectionState,
    modifier: Modifier = Modifier
) {
    val (label, textColor, bgColor) = when (state) {
        is VpnConnectionState.Connected -> Triple("PROTECTED", NeonEmerald, NeonEmeraldContainer)
        is VpnConnectionState.Connecting -> Triple("CONNECTING", ElectricCyan, ElectricCyanContainer)
        is VpnConnectionState.Reconnecting -> Triple("AUTO-SWITCH", WarningAmber, WarningAmberContainer)
        is VpnConnectionState.Error -> Triple("ATTENTION", AlertCoral, AlertCoralContainer)
        is VpnConnectionState.Disconnected -> Triple("UNPROTECTED", TextSecondary, CyberSurface)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "statusDot")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val dotAlpha = if (state is VpnConnectionState.Connecting || state is VpnConnectionState.Reconnecting || state is VpnConnectionState.Connected) {
        pulseAlpha
    } else {
        1f
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .border(1.dp, textColor.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(textColor.copy(alpha = dotAlpha))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}

@Composable
fun PingBadge(
    pingMs: Int?,
    tier: PingTier,
    modifier: Modifier = Modifier
) {
    val (text, color, bgColor, activeBars) = when (tier) {
        PingTier.FAST -> Quadruple("${pingMs} ms", NeonEmerald, NeonEmeraldContainer, 3)
        PingTier.MEDIUM -> Quadruple("${pingMs} ms", WarningAmber, WarningAmberContainer, 2)
        PingTier.SLOW -> Quadruple("${pingMs} ms", AlertCoral, AlertCoralContainer, 1)
        PingTier.UNREACHABLE -> Quadruple("Timeout", AlertCoral, AlertCoralContainer, 0)
        PingTier.UNTESTED -> Quadruple("— ms", TextMuted, CyberSurface, 0)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(width = 14.dp, height = 11.dp)) {
            val barWidth = 2.6.dp.toPx()
            val gap = 2.dp.toPx()
            val heights = listOf(0.38f, 0.68f, 1f)
            heights.forEachIndexed { idx, frac ->
                val x = idx * (barWidth + gap) + barWidth / 2f
                val barH = size.height * frac
                drawLine(
                    color = if (idx < activeBars) color else TextMuted.copy(alpha = 0.35f),
                    start = Offset(x, size.height),
                    end = Offset(x, size.height - barH),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
