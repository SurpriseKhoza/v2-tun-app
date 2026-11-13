package io.surprise.vpn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.surprise.vpn.ui.theme.Aqua
import io.surprise.vpn.ui.theme.Coral
import io.surprise.vpn.ui.theme.Violet
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(26_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(7_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )
    Box(modifier = modifier.fillMaxSize().background(scheme.background)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val min = size.minDimension
            val alpha = if (isDark) 1f else 0.48f
            drawRect(scheme.background)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Aqua.copy(alpha = 0.11f * alpha), Aqua.copy(alpha = 0.025f * alpha), Color.Transparent),
                    center = Offset(w * 0.13f + sin(phase) * w * 0.06f, h * 0.08f + cos(phase * 0.7f) * h * 0.03f),
                    radius = min * 0.9f
                ),
                radius = min * 0.9f,
                center = Offset(w * 0.13f + sin(phase) * w * 0.06f, h * 0.08f + cos(phase * 0.7f) * h * 0.03f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Violet.copy(alpha = 0.10f * alpha), Violet.copy(alpha = 0.022f * alpha), Color.Transparent),
                    center = Offset(w * 0.96f + cos(phase * 0.8f) * w * 0.04f, h * 0.38f + sin(phase) * h * 0.08f),
                    radius = min * 0.85f * breathe
                ),
                radius = min * 0.85f * breathe,
                center = Offset(w * 0.96f + cos(phase * 0.8f) * w * 0.04f, h * 0.38f + sin(phase) * h * 0.08f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Coral.copy(alpha = 0.05f * alpha), Color.Transparent),
                    center = Offset(w * 0.45f + sin(phase * 0.6f) * w * 0.1f, h * 0.92f),
                    radius = min * 0.7f
                ),
                radius = min * 0.7f,
                center = Offset(w * 0.45f + sin(phase * 0.6f) * w * 0.1f, h * 0.92f)
            )
            // Sparse "signal field" dots add depth without visual noise.
            val step = 46.dp.toPx()
            var x = step / 2
            while (x < w) {
                var y = step / 2
                while (y < h) {
                    drawCircle(
                        color = scheme.onBackground.copy(alpha = if (isDark) 0.028f else 0.018f),
                        radius = 0.9.dp.toPx(),
                        center = Offset(x, y)
                    )
                    y += step
                }
                x += step
            }
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val base = modifier
        .clip(shape)
        .background(scheme.surface.copy(alpha = 0.94f))
        .border(1.dp, scheme.outline.copy(alpha = 0.42f), shape)
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(
        modifier = clickable.padding(contentPadding),
        content = content
    )
}

@Composable
fun SoftSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    contentPadding: PaddingValues = PaddingValues(14.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val base = modifier
        .clip(shape)
        .background(scheme.surfaceVariant.copy(alpha = 0.48f))
        .border(1.dp, scheme.outline.copy(alpha = 0.34f), shape)
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(
        modifier = clickable.padding(contentPadding),
        content = content
    )
}

@Composable
fun TopIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 42.dp
) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(scheme.surface.copy(alpha = 0.96f))
            .border(1.dp, scheme.outline.copy(alpha = 0.8f), CircleShape)
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun RowScope.QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.38f))
            .border(1.dp, scheme.outline.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(21.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
