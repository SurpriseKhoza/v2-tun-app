package io.surprise.vpn.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.surprise.vpn.data.model.ConnectionStatus
import io.surprise.vpn.data.model.VpnProtocol
import io.surprise.vpn.ui.theme.Aqua
import io.surprise.vpn.ui.theme.AquaSoft
import io.surprise.vpn.ui.theme.BrandSweep
import io.surprise.vpn.ui.theme.Coral
import io.surprise.vpn.ui.theme.Danger
import io.surprise.vpn.ui.theme.Success
import io.surprise.vpn.ui.theme.Violet
import io.surprise.vpn.ui.theme.Warning
import kotlin.math.sin

@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.085f
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.24f)
            lineTo(w * 0.44f, h * 0.67f)
            quadraticBezierTo(w * 0.5f, h * 0.8f, w * 0.56f, h * 0.67f)
            lineTo(w * 0.78f, h * 0.24f)
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(listOf(AquaSoft, Aqua, Violet)),
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawArc(
            color = Aqua.copy(alpha = 0.45f),
            startAngle = 18f,
            sweepAngle = 144f,
            useCenter = false,
            topLeft = Offset(w * 0.13f, h * 0.55f),
            size = Size(w * 0.74f, h * 0.34f),
            style = Stroke(width = w * 0.024f, cap = StrokeCap.Round)
        )
        drawCircle(Color.White, radius = w * 0.083f, center = Offset(w * 0.77f, h * 0.23f))
        drawCircle(Color(0xFF06131F), radius = w * 0.036f, center = Offset(w * 0.77f, h * 0.23f))
    }
}

@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        BrandMark(size = if (compact) 25.dp else 32.dp)
        Spacer(Modifier.width(if (compact) 7.dp else 9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "V2",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 17.sp else 20.sp,
                    letterSpacing = (-0.6).sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "—",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 1.dp)
            )
            Text(
                "TUN",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = if (compact) 1.6.sp else 2.sp,
                    fontSize = if (compact) 12.sp else 14.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
fun statusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> Success
    ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTING -> Warning
    ConnectionStatus.ERROR -> Danger
    ConnectionStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun StatusPill(
    status: ConnectionStatus,
    modifier: Modifier = Modifier
) {
    val color = statusColor(status)
    val label = when (status) {
        ConnectionStatus.CONNECTED -> "Protected"
        ConnectionStatus.CONNECTING -> "Securing"
        ConnectionStatus.DISCONNECTING -> "Closing"
        ConnectionStatus.ERROR -> "Attention"
        ConnectionStatus.STOPPED -> "Offline"
    }
    val transition = rememberInfiniteTransition(label = "statusPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusAlpha"
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.34f), RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(14.dp)) {
                drawCircle(color = color.copy(alpha = if (status == ConnectionStatus.CONNECTED) 0.18f * pulse else 0.10f))
            }
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
fun ProtocolBadge(
    label: String,
    modifier: Modifier = Modifier,
    protocol: VpnProtocol? = null,
    json: Boolean = false
) {
    val tint = when {
        json -> MaterialTheme.colorScheme.secondary
        protocol == VpnProtocol.VMESS -> Violet
        protocol == VpnProtocol.TROJAN -> Warning
        protocol == VpnProtocol.SHADOWSOCKS -> Coral
        else -> Aqua
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
            color = tint
        )
    }
}

@Composable
fun ConnectionOrb(
    status: ConnectionStatus,
    hasProfile: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val active = status == ConnectionStatus.CONNECTED
    val busy = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.DISCONNECTING
    val transition = rememberInfiniteTransition(label = "orb")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (busy) 1900 else 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbRotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbPulse"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "orbPress"
    )
    val ringColor = if (active) Aqua else scheme.onSurfaceVariant

    Box(modifier = modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val min = size.minDimension
            drawCircle(ringColor.copy(alpha = 0.025f), radius = min * 0.5f)
            drawCircle(
                color = ringColor.copy(alpha = 0.10f),
                radius = min * 0.47f,
                style = Stroke(1.dp.toPx())
            )
            drawCircle(
                color = ringColor.copy(alpha = 0.07f),
                radius = min * 0.39f,
                style = Stroke(1.dp.toPx())
            )
            drawArc(
                brush = BrandSweep,
                startAngle = rotation,
                sweepAngle = if (busy) 135f else 82f,
                useCenter = false,
                topLeft = Offset(min * 0.015f, min * 0.015f),
                size = Size(min * 0.97f, min * 0.97f),
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
                alpha = if (active || busy) 0.95f else 0.34f
            )
            if (active) {
                drawArc(
                    color = Violet.copy(alpha = 0.6f),
                    startAngle = -rotation * 0.7f + 140f,
                    sweepAngle = 58f,
                    useCenter = false,
                    topLeft = Offset(min * 0.08f, min * 0.08f),
                    size = Size(min * 0.84f, min * 0.84f),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            // orbital spark
            val angle = Math.toRadians((rotation * 0.75).toDouble())
            val orbit = min * 0.485f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = if (active) Aqua else scheme.onSurfaceVariant.copy(alpha = 0.45f),
                radius = 3.2.dp.toPx(),
                center = Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * orbit,
                    center.y + kotlin.math.sin(angle).toFloat() * orbit
                )
            )
        }

        Box(
            modifier = Modifier
                .size(176.dp)
                .graphicsLayer {
                    scaleX = if (active) pulse else 1f
                    scaleY = if (active) pulse else 1f
                }
                .background(
                    Brush.radialGradient(
                        listOf(
                            (if (active) Aqua else scheme.primary).copy(alpha = 0.24f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )

        Box(
            modifier = Modifier
                .size(146.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .background(
                    if (active || busy) {
                        Brush.linearGradient(listOf(Aqua, Color(0xFF65E7FF), Violet))
                    } else {
                        Brush.linearGradient(
                            listOf(scheme.surfaceVariant, scheme.surface)
                        )
                    }
                )
                .border(
                    1.dp,
                    if (active) Color.White.copy(alpha = 0.35f) else scheme.outline.copy(alpha = 0.7f),
                    CircleShape
                )
                .clickable(
                    enabled = !busy,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = when {
                        active -> "Disconnect VPN"
                        hasProfile -> "Connect VPN"
                        else -> "Add a configuration"
                    },
                    tint = if (active || busy) Color(0xFF031612) else scheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        status == ConnectionStatus.CONNECTING -> "CONNECTING"
                        status == ConnectionStatus.DISCONNECTING -> "CLOSING"
                        active -> "TAP TO DISCONNECT"
                        !hasProfile -> "SET UP A SERVER"
                        else -> "TAP TO CONNECT"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                    color = if (active || busy) Color(0xFF031612).copy(alpha = 0.72f) else scheme.onSurfaceVariant
                )
            }
        }
    }
}
