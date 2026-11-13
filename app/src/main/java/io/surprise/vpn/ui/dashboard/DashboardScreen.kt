package io.surprise.vpn.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.surprise.vpn.MainViewModel
import io.surprise.vpn.data.model.ConnectionStatus
import io.surprise.vpn.data.model.VpnProfile
import io.surprise.vpn.ui.components.ConnectionOrb
import io.surprise.vpn.ui.components.BrandLockup
import io.surprise.vpn.ui.components.GlassCard
import io.surprise.vpn.ui.components.ProtocolBadge
import io.surprise.vpn.ui.components.QuickAction
import io.surprise.vpn.ui.components.SectionHeader
import io.surprise.vpn.ui.components.StatusPill
import io.surprise.vpn.ui.components.TopIconButton
import io.surprise.vpn.ui.theme.Aqua
import io.surprise.vpn.ui.theme.Coral
import io.surprise.vpn.ui.theme.Violet
import io.surprise.vpn.util.formatBytes
import io.surprise.vpn.util.formatDuration
import io.surprise.vpn.util.formatLatency
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onSettings: () -> Unit,
    onOpenConfigs: () -> Unit,
    onOpenEditor: (String?) -> Unit,
    onOpenTools: () -> Unit,
    onOpenLogs: () -> Unit,
    onConnectRequested: () -> Unit,
    onDisconnectRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tunnel by viewModel.tunnel.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tunnel.connectedAt) {
        while (tunnel.connectedAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val onOrbClick = {
        if (settings.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        when {
            tunnel.isBusy -> Unit
            tunnel.status == ConnectionStatus.CONNECTED -> onDisconnectRequested()
            selected != null -> onConnectRequested()
            else -> onOpenEditor(null)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopIconButton(
                    icon = Icons.Rounded.Settings,
                    contentDescription = "Open VPN settings",
                    onClick = onSettings
                )
                Spacer(Modifier.weight(1f))
                BrandLockup(compact = true)
                Spacer(Modifier.weight(1f))
                StatusPill(tunnel.status)
            }
        }

        item {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "COMMAND CENTER",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (selected == null) "Your private route,\nready when you are." else "Your private route,\nsealed with Xray.",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        item {
            ConnectionHero(
                status = tunnel.status,
                selected = selected,
                profileName = tunnel.profileName.ifBlank { selected?.name.orEmpty() },
                endpoint = tunnel.endpoint.ifBlank { selected?.endpoint.orEmpty() },
                connectedAt = tunnel.connectedAt,
                now = now,
                error = tunnel.error,
                protocolLabel = if (selected?.isJsonBacked == true) "JSON" else selected?.protocol?.label.orEmpty(),
                json = selected?.isJsonBacked == true,
                onOrbClick = onOrbClick,
                onAddConfig = { onOpenEditor(null) },
                onManage = onOpenConfigs
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    icon = Icons.Rounded.ArrowDownward,
                    label = "Download",
                    value = formatBytes(tunnel.downloadBytes),
                    accent = Aqua,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    icon = Icons.Rounded.ArrowUpward,
                    label = "Upload",
                    value = formatBytes(tunnel.uploadBytes),
                    accent = Violet,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    icon = Icons.Rounded.Speed,
                    label = "Latency",
                    value = formatLatency(tunnel.latencyMs),
                    accent = Coral,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            AnimatedVisibility(visible = tunnel.error != null) {
                ErrorBanner(
                    message = tunnel.error.orEmpty(),
                    onRetry = onConnectRequested
                )
            }
        }

        item {
            SectionHeader(
                title = "Active route",
                eyebrow = "PROFILE",
                actionLabel = if (profiles.isEmpty()) "Create" else "Manage",
                onAction = { if (profiles.isEmpty()) onOpenEditor(null) else onOpenConfigs() }
            )
        }

        item {
            if (selected == null) {
                EmptyRouteCard(onCreate = { onOpenEditor(null) })
            } else {
                ActiveRouteCard(
                    profile = selected!!,
                    status = tunnel.status,
                    onClick = onOpenConfigs
                )
            }
        }

        item {
            SectionHeader(title = "Quick controls", eyebrow = "TOOLS")
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickAction(
                    icon = Icons.Rounded.Power,
                    label = "Test exit IP",
                    onClick = {
                        viewModel.testExitInfo()
                        onOpenTools()
                    }
                )
                QuickAction(
                    icon = Icons.Rounded.Speed,
                    label = "Ping server",
                    onClick = {
                        viewModel.pingServer()
                        onOpenTools()
                    }
                )
                QuickAction(
                    icon = Icons.Rounded.Terminal,
                    label = "View logs",
                    onClick = onOpenLogs
                )
            }
        }

        item {
            GlassCard(
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(17.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Your configurations never leave this device",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "V2-TUN keeps profiles in local storage and hands the server credentials directly to the Xray core. No account, no cloud sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (tunnel.status == ConnectionStatus.CONNECTED) Aqua else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    if (tunnel.status == ConnectionStatus.CONNECTED) {
                        "Tunneled via ${tunnel.networkLabel.ifBlank { "network" }}"
                    } else {
                        "V2-TUN · Xray core ${tunnel.coreVersion}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ConnectionHero(
    status: ConnectionStatus,
    selected: VpnProfile?,
    profileName: String,
    endpoint: String,
    connectedAt: Long?,
    now: Long,
    error: String?,
    protocolLabel: String,
    json: Boolean,
    onOrbClick: () -> Unit,
    onAddConfig: () -> Unit,
    onManage: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val active = status == ConnectionStatus.CONNECTED
    val headline = when {
        status == ConnectionStatus.CONNECTING -> "Securing your tunnel"
        status == ConnectionStatus.DISCONNECTING -> "Closing the tunnel"
        active -> "Tunnel secured"
        status == ConnectionStatus.ERROR -> "Tunnel needs attention"
        selected == null -> "No server configured"
        else -> "Ready when you are"
    }
    val detail = when {
        status == ConnectionStatus.CONNECTING -> "Negotiating with $profileName"
        status == ConnectionStatus.DISCONNECTING -> "Releasing the local TUN interface"
        active -> "Traffic is encrypted through $profileName"
        status == ConnectionStatus.ERROR -> error ?: "Check your configuration and try again."
        selected == null -> "Bring your own VLESS, VMess, Trojan, Shadowsocks, or Xray JSON server."
        else -> "Tap the shield to route your traffic through $endpoint."
    }
    val scale by animateFloatAsState(if (active) 1f else 0.96f, label = "heroScale")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        scheme.surface.copy(alpha = 0.96f),
                        scheme.surface.copy(alpha = 0.72f),
                        scheme.primary.copy(alpha = 0.035f)
                    )
                )
            )
            .border(1.dp, scheme.outline.copy(alpha = 0.62f), RoundedCornerShape(32.dp))
            .animateContentSize()
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        (if (active) Aqua else scheme.primary).copy(alpha = 0.11f),
                        Color.Transparent
                    )
                ),
                radius = size.minDimension * 0.8f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.42f)
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (active) Aqua else scheme.onSurfaceVariant.copy(alpha = 0.5f))
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "LIVE TUNNEL",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                        color = scheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                if (selected != null) {
                    ProtocolBadge(protocolLabel, protocol = selected.protocol, json = json)
                }
            }

            Box(
                modifier = Modifier.padding(vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                ConnectionOrb(
                    status = status,
                    hasProfile = selected != null,
                    onClick = onOrbClick,
                    modifier = Modifier.graphicsLayerScale(scale)
                )
            }

            Text(
                headline,
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(7.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 22.dp)
            )
            if (active && connectedAt != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    formatDuration(connectedAt, now),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = io.surprise.vpn.ui.theme.JetBrainsMono,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    ),
                    color = scheme.primary
                )
            }
            if (selected == null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(scheme.primary.copy(alpha = 0.12f))
                        .border(1.dp, scheme.primary.copy(alpha = 0.3f), RoundedCornerShape(50))
                        .clickable(onClick = onAddConfig)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Add your first configuration",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Rounded.ArrowForward, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                }
            }
            if (selected != null && !active) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onManage)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.ArrowForward,
                        null,
                        tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

private fun Modifier.graphicsLayerScale(scale: Float): Modifier =
    this.then(Modifier.graphicsLayer { scaleX = scale; scaleY = scale })

@Composable
private fun MetricCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.28f))
            .border(1.dp, scheme.outline.copy(alpha = 0.34f), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
            color = scheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun ActiveRouteCard(
    profile: VpnProfile,
    status: ConnectionStatus,
    onClick: () -> Unit
) {
    GlassCard(
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(17.dp),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    profile.endpoint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProtocolBadge(
                        if (profile.isJsonBacked) "Xray JSON" else profile.protocol.label,
                        protocol = profile.protocol,
                        json = profile.isJsonBacked
                    )
                    ProtocolBadge(profile.securityLabel)
                }
            }
            if (status == ConnectionStatus.CONNECTED) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Active route",
                    tint = Aqua,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Icon(
                    Icons.Rounded.ArrowForward,
                    contentDescription = "Open configurations",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyRouteCard(onCreate: () -> Unit) {
    GlassCard(
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(20.dp),
        onClick = onCreate
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Create a configuration", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Guided form or raw Xray JSON",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.error.copy(alpha = 0.09f))
            .border(1.dp, scheme.error.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = scheme.error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.error,
            modifier = Modifier.weight(1f)
        )
        Text(
            "Retry",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetry)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
