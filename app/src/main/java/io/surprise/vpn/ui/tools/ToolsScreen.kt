package io.surprise.vpn.ui.tools

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.surprise.vpn.MainViewModel
import io.surprise.vpn.data.ShareLinkParser
import io.surprise.vpn.data.model.ConnectionStatus
import io.surprise.vpn.ui.components.ConfirmDialog
import io.surprise.vpn.ui.components.GlassCard
import io.surprise.vpn.ui.components.GradientButton
import io.surprise.vpn.ui.components.OutlineButton
import io.surprise.vpn.ui.components.SectionHeader
import io.surprise.vpn.ui.components.SmallActionChip
import io.surprise.vpn.ui.components.StatusPill
import io.surprise.vpn.ui.components.TextInputDialog
import io.surprise.vpn.ui.components.ToolTile
import io.surprise.vpn.ui.theme.Aqua
import io.surprise.vpn.ui.theme.Coral
import io.surprise.vpn.ui.theme.Success
import io.surprise.vpn.ui.theme.Violet
import io.surprise.vpn.ui.theme.Warning
import io.surprise.vpn.util.formatLatency
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ToolsScreen(
    viewModel: MainViewModel,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tunnel by viewModel.tunnel.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val exitInfo by viewModel.exitInfo.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val dnsResult by viewModel.dnsResult.collectAsStateWithLifecycle()
    val pingResult by viewModel.pingResult.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    var showDnsDialog by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(viewModel.backupJson().toByteArray())
                }
            }.onSuccess { viewModel.notify("Backup saved") }
                .onFailure { viewModel.notify("Could not write the backup.", isError = true) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.onSuccess { text ->
                if (!text.isNullOrBlank()) pendingRestore = text
            }.onFailure { viewModel.notify("Could not read that backup.", isError = true) }
        }
    }

    fun openSystem(action: String, packageUri: Uri? = null, appPackage: String? = null) {
        runCatching {
            val intent = Intent(action).apply {
                if (packageUri != null) data = packageUri
                if (appPackage != null) putExtra(Settings.EXTRA_APP_PACKAGE, appPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            viewModel.notify("This system settings page is not available on this device.", isError = true)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("NETWORK INTELLIGENCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(5.dp))
                    Text("Tools", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Verify your route. Keep your setup healthy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(tunnel.status)
            }
        }

        item {
            ExitIpHero(
                tunnelStatus = tunnel.status,
                exitInfo = exitInfo,
                isTesting = isTesting,
                onTest = viewModel::testExitInfo
            )
        }

        item {
            SectionHeader(title = "Network checks", eyebrow = "DIAGNOSTICS")
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolTile(
                    title = "DNS lookup",
                    subtitle = "Resolve a hostname",
                    icon = Icons.Rounded.Dns,
                    accent = Violet,
                    modifier = Modifier.weight(1f),
                    onClick = { showDnsDialog = true }
                )
                ToolTile(
                    title = "TCP check",
                    subtitle = selected?.endpoint ?: "Select a server",
                    icon = Icons.Rounded.Lan,
                    accent = Aqua,
                    modifier = Modifier.weight(1f),
                    onClick = viewModel::pingServer
                )
            }
        }

        if (dnsResult != null || pingResult != null) {
            item {
                ResultsCard(
                    dnsResult = dnsResult,
                    pingResult = pingResult
                )
            }
        }

        item {
            SectionHeader(title = "Device controls", eyebrow = "SYSTEM")
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolTile(
                    title = "Always-on VPN",
                    subtitle = "Configure Android's kill switch",
                    icon = Icons.Rounded.Security,
                    accent = Coral,
                    modifier = Modifier.weight(1f),
                    onClick = { openSystem(Settings.ACTION_VPN_SETTINGS) }
                )
                ToolTile(
                    title = "Battery",
                    subtitle = "Keep the tunnel alive",
                    icon = Icons.Rounded.BatterySaver,
                    accent = Warning,
                    modifier = Modifier.weight(1f),
                    onClick = { openSystem(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolTile(
                    title = "Notifications",
                    subtitle = "Tunnel status controls",
                    icon = Icons.Rounded.NotificationsActive,
                    accent = Violet,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        openSystem(
                            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                            appPackage = context.packageName
                        )
                    }
                )
                ToolTile(
                    title = "Quick Settings",
                    subtitle = "Add the V2-TUN tile",
                    icon = Icons.Rounded.WifiTethering,
                    accent = Aqua,
                    modifier = Modifier.weight(1f),
                    onClick = { openSystem("android.settings.QUICK_SETTINGS") }
                )
            }
        }

        item {
            SectionHeader(title = "Vault & logs", eyebrow = "LOCAL DATA")
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolTile(
                    title = "Backup",
                    subtitle = "Export profiles as JSON",
                    icon = Icons.Rounded.Backup,
                    accent = Aqua,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        exportLauncher.launch("v2tun-backup-$stamp.json")
                    }
                )
                ToolTile(
                    title = "Restore",
                    subtitle = "Merge a local backup",
                    icon = Icons.Rounded.Restore,
                    accent = Violet,
                    modifier = Modifier.weight(1f),
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolTile(
                    title = "Share active route",
                    subtitle = "Send a share link",
                    icon = Icons.Rounded.Share,
                    accent = Coral,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val profile = selected
                        if (profile == null) {
                            viewModel.notify("Select a configuration first.", isError = true)
                        } else {
                            runCatching {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, profile.name)
                                    putExtra(Intent.EXTRA_TEXT, ShareLinkParser.toShareLink(profile))
                                }
                                context.startActivity(Intent.createChooser(intent, "Share ${profile.name}"))
                            }.onFailure { viewModel.notify("This configuration could not be shared.", isError = true) }
                        }
                    }
                )
                ToolTile(
                    title = "Full logs",
                    subtitle = "${logs.size} entries this session",
                    icon = Icons.Rounded.Terminal,
                    accent = Warning,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenLogs
                )
            }
        }

        if (logs.isNotEmpty()) {
            item {
                GlassCard(
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(15.dp),
                    onClick = onOpenLogs
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Latest log", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        logs.last().message,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = io.surprise.vpn.ui.theme.JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        item {
            GlassCard(
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Xray core ${tunnel.coreVersion}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Network checks use a short-lived, authenticated localhost proxy while the tunnel is active. No credentials are shown in the UI.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (tunnel.socksPort != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(Success))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Diagnostic channel active on 127.0.0.1:${tunnel.socksPort}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Success
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlineButton(
                    text = "Open settings",
                    onClick = onOpenSettings,
                    icon = Icons.Rounded.Settings,
                    modifier = Modifier.weight(1f)
                )
                OutlineButton(
                    text = "Clear results",
                    onClick = viewModel::clearDiagnostics,
                    icon = Icons.Rounded.ContentCopy,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showDnsDialog) {
        TextInputDialog(
            title = "DNS lookup",
            label = "Hostname",
            initialValue = "example.com",
            placeholder = "example.com",
            confirmLabel = "Resolve",
            onConfirm = { viewModel.lookupDns(it) },
            onDismiss = { showDnsDialog = false }
        )
    }

    pendingRestore?.let { text ->
        ConfirmDialog(
            title = "Restore backup?",
            text = "Configurations in this backup will be merged into your local vault. Existing configurations are kept.",
            confirmLabel = "Restore",
            onConfirm = { viewModel.restoreBackup(text, replace = false) },
            onDismiss = { pendingRestore = null }
        )
    }
}

@Composable
private fun ExitIpHero(
    tunnelStatus: ConnectionStatus,
    exitInfo: io.surprise.vpn.data.ExitInfo?,
    isTesting: Boolean,
    onTest: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    GlassCard(
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("EXIT IP", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                Spacer(Modifier.height(5.dp))
                Text(
                    if (exitInfo == null) "See what the internet sees" else exitInfo.ip,
                    style = if (exitInfo == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Public, null, tint = scheme.primary, modifier = Modifier.size(23.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (exitInfo == null) {
            Text(
                if (tunnelStatus == ConnectionStatus.CONNECTED) {
                    "Run a check through the active tunnel to confirm your exit route."
                } else {
                    "Connect a tunnel first to see your protected exit IP. A direct check is still available."
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (exitInfo.viaTunnel) {
                    StatusBadge("Via tunnel", Success)
                } else {
                    StatusBadge("Direct network", Warning)
                }
                if (exitInfo.latencyMs > 0) {
                    StatusBadge("${exitInfo.latencyMs} ms", scheme.primary)
                }
            }
            if (exitInfo.country.isNotBlank() || exitInfo.city.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    listOf(exitInfo.city, exitInfo.country).filter { it.isNotBlank() }.joinToString(", "),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (exitInfo.organization.isNotBlank()) {
                Text(
                    exitInfo.organization,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        GradientButton(
            text = if (isTesting) "Checking route…" else "Run exit check",
            onClick = onTest,
            enabled = !isTesting,
            icon = if (isTesting) null else Icons.Rounded.CloudDownload
        )
        if (isTesting) {
            Spacer(Modifier.height(10.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = scheme.primary,
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ResultsCard(
    dnsResult: io.surprise.vpn.data.DnsResult?,
    pingResult: io.surprise.vpn.data.PingResult?
) {
    GlassCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        if (dnsResult != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Dns, null, tint = Violet, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("DNS · ${dnsResult.host}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${dnsResult.latencyMs} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(7.dp))
            dnsResult.addresses.take(4).forEach { address ->
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = io.surprise.vpn.ui.theme.JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (dnsResult != null && pingResult != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            )
            Spacer(Modifier.height(12.dp))
        }
        if (pingResult != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Success, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("TCP reachable · ${pingResult.host}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Port ${pingResult.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatLatency(pingResult.latencyMs), style = MaterialTheme.typography.labelLarge, color = Success)
            }
        }
    }
}
