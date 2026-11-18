package io.surprise.vpn.ui.dashboard

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.surprise.vpn.BuildConfig
import io.surprise.vpn.MainViewModel
import io.surprise.vpn.core.TunnelSnapshot
import io.surprise.vpn.data.model.ConnectionStatus
import io.surprise.vpn.data.model.VpnProfile
import io.surprise.vpn.ui.components.ConnectionOrb
import io.surprise.vpn.ui.components.TopIconButton
import io.surprise.vpn.ui.theme.Aqua
import io.surprise.vpn.util.formatBytes
import io.surprise.vpn.util.formatDuration
import io.surprise.vpn.util.formatLatency
import kotlinx.coroutines.delay

@Composable
fun NpvDashboardScreen(
    viewModel: MainViewModel,
    onSettings: () -> Unit,
    onOpenConfigs: () -> Unit,
    onOpenEditor: (String?) -> Unit,
    onConnectRequested: () -> Unit,
    onDisconnectRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val tunnel by viewModel.tunnel.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tunnel.connectedAt) {
        while (tunnel.connectedAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val connected = tunnel.status == ConnectionStatus.CONNECTED
    val orbClick = {
        when {
            // A connect that is still in progress can be cancelled.
            tunnel.status == ConnectionStatus.CONNECTING -> onDisconnectRequested()
            tunnel.status == ConnectionStatus.DISCONNECTING -> Unit
            connected -> onDisconnectRequested()
            selected != null -> onConnectRequested()
            else -> onOpenEditor(null)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth()) {
            TopIconButton(
                icon = Icons.Rounded.Settings,
                contentDescription = "VPN settings",
                onClick = onSettings,
                size = 36.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                modifier = Modifier.align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "V2-TUN",
                    style = MaterialTheme.typography.titleMedium.copy(
                        letterSpacing = 4.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Aqua
                )
                Text(
                    "v${BuildConfig.VERSION_NAME} · Xray / SSH",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(30.dp))
        ConnectionOrb(
            status = tunnel.status,
            hasProfile = selected != null,
            onClick = orbClick,
            modifier = Modifier.size(240.dp)
        )
        Spacer(Modifier.height(10.dp))

        StatusPanel(
            status = tunnel.status,
            selected = selected,
            profileName = tunnel.profileName.ifBlank { selected?.name.orEmpty() },
            error = tunnel.error
        )
        Spacer(Modifier.height(12.dp))
        RouteStrip(
            connected = connected,
            hasProfile = selected != null,
            onClick = if (selected == null) ({ onOpenEditor(null) }) else onOpenConfigs
        )
        Spacer(Modifier.height(16.dp))
        ActiveConfigurationPanel(
            profile = selected,
            connected = connected,
            tunnel = tunnel,
            now = now,
            onClick = if (selected == null) ({ onOpenEditor(null) }) else onOpenConfigs
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun StatusPanel(
    status: ConnectionStatus,
    selected: VpnProfile?,
    profileName: String,
    error: String?
) {
    val connected = status == ConnectionStatus.CONNECTED
    val title = when {
        connected -> "CONNECTED"
        status == ConnectionStatus.CONNECTING -> "CONNECTING"
        status == ConnectionStatus.DISCONNECTING -> "DISCONNECTING"
        status == ConnectionStatus.ERROR -> "CONNECTION ERROR"
        else -> "NOT CONNECTED"
    }
    val detail = when {
        connected -> "Your traffic is routed through $profileName."
        status == ConnectionStatus.CONNECTING -> "Tap the power button to cancel."
        status == ConnectionStatus.ERROR -> error ?: "Check the configuration and try again."
        selected == null -> "Choose a configuration, then tap the power button to connect."
        else -> "Tap the power button to start the selected tunnel."
    }
    val scheme = MaterialTheme.colorScheme
    val icon = when (status) {
        ConnectionStatus.CONNECTED -> Icons.Rounded.CloudDone
        ConnectionStatus.ERROR -> Icons.Rounded.ErrorOutline
        ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTING -> Icons.Rounded.Sync
        ConnectionStatus.STOPPED -> Icons.Rounded.CloudOff
    }
    val iconTint = when (status) {
        ConnectionStatus.CONNECTED -> Aqua
        ConnectionStatus.ERROR -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Announce status changes to screen readers.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surface.copy(alpha = 0.9f))
            .border(1.dp, scheme.outline.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.width(15.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 1.2.sp))
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RouteStrip(connected: Boolean, hasProfile: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (connected) Aqua else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                connected -> "Secure route active"
                hasProfile -> "Ready · tap to change configuration"
                else -> "Select a configuration"
            },
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.ArrowForwardIos, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun ActiveConfigurationPanel(
    profile: VpnProfile?,
    connected: Boolean,
    tunnel: TunnelSnapshot,
    now: Long,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 20.dp)
    ) {
        Text("ACTIVE CONFIGURATION", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Dns, null, tint = Aqua, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile?.name ?: "Not Set",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    profile?.endpoint ?: "---",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Rounded.ArrowForwardIos, null, tint = Aqua, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Ping", style = MaterialTheme.typography.labelSmall, color = Aqua)
                Text(formatLatency(tunnel.latencyMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (connected && tunnel.connectedAt != null) formatDuration(tunnel.connectedAt, now) else "---", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TrafficMetric(Icons.Rounded.ArrowDownward, "DOWNLINK", formatBytes(tunnel.downloadBytes))
            TrafficMetric(Icons.Rounded.ArrowUpward, "UPLINK", formatBytes(tunnel.uploadBytes))
        }
    }
}

@Composable
private fun TrafficMetric(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(7.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}
