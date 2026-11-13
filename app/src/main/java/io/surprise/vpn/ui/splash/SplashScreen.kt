package io.surprise.vpn.ui.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.surprise.vpn.R
import io.surprise.vpn.ui.theme.Aqua
import io.surprise.vpn.ui.theme.AquaSoft
import io.surprise.vpn.ui.theme.Ink
import io.surprise.vpn.ui.theme.Violet
import io.surprise.vpn.ui.theme.VioletSoft

@Composable
fun V2TunSplashScreen(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "splash-motion")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-phase"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000), RepeatMode.Restart),
        label = "ring-rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "icon-pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ink),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val travel = minOf(w, h) * 0.18f
            val aquaCenter = Offset(w * 0.18f + travel * phase, h * 0.18f + travel * (1f - phase))
            val violetCenter = Offset(w * 0.88f - travel * phase, h * 0.72f - travel * (1f - phase))
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0E2A31), Color(0xFF071923), Ink),
                    center = Offset(w * 0.5f, h * 0.42f),
                    radius = maxOf(w, h) * 0.85f
                )
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Aqua.copy(alpha = 0.18f), Color.Transparent)),
                radius = h * 0.34f,
                center = aquaCenter
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Violet.copy(alpha = 0.16f), Color.Transparent)),
                radius = h * 0.38f,
                center = violetCenter
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(210.dp).scale(pulse)) {
                    val center = center
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(Aqua.copy(alpha = 0.05f), AquaSoft.copy(alpha = 0.75f), VioletSoft.copy(alpha = 0.6f), Aqua.copy(alpha = 0.05f))
                        ),
                        radius = size.minDimension * 0.43f,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, Aqua, Violet, Color.Transparent)),
                        startAngle = rotation,
                        sweepAngle = 105f,
                        useCenter = false,
                        topLeft = Offset(size.width * 0.15f, size.height * 0.15f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.7f, size.height * 0.7f),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF173A43), Color(0xFF0B202B))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "V2-TUN",
                        modifier = Modifier.size(92.dp)
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = "V2-TUN",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AquaSoft
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "SECURE NETWORK ACCESS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(0.82f)
            )
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .size(width = 54.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(listOf(Aqua, Violet, CoralAccent))
                    )
            )
        }
    }
}

private val CoralAccent = Color(0xFFFF718F)
