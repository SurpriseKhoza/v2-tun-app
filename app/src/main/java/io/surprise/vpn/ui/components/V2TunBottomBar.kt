package io.surprise.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class V2TunNavItem(val route: String, val label: String, val icon: ImageVector)

val v2TunNavItems = listOf(
    V2TunNavItem("dashboard", "Home", Icons.Rounded.Dashboard),
    V2TunNavItem("configs", "Configs", Icons.Rounded.Dns),
    V2TunNavItem("logs", "Logs", Icons.Rounded.Info),
    V2TunNavItem("settings", "More", Icons.Rounded.Settings)
)

@Composable
fun V2TunBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surface.copy(alpha = 0.98f))
            .border(1.dp, scheme.outline.copy(alpha = 0.52f), RoundedCornerShape(18.dp))
            .padding(horizontal = 5.dp, vertical = 5.dp)
            .height(58.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        v2TunNavItems.forEach { item ->
            val selected = currentRoute == item.route
            val progress by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "navProgress")
            val tint by animateColorAsState(
                if (selected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.78f),
                tween(180), label = "navTint"
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(13.dp))
                    .background(scheme.primary.copy(alpha = 0.13f * progress))
                    .clickable { onNavigate(item.route) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(19.dp))
                Spacer(Modifier.height(3.dp))
                Text(item.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = tint)
            }
        }
    }
}
