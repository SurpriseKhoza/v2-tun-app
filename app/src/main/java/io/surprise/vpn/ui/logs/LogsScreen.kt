package io.surprise.vpn.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.surprise.vpn.MainViewModel
import io.surprise.vpn.core.LogSource
import io.surprise.vpn.ui.components.SmallActionChip
import io.surprise.vpn.ui.components.TopIconButton
import io.surprise.vpn.ui.theme.Danger
import io.surprise.vpn.ui.theme.JsonTextStyle
import io.surprise.vpn.ui.theme.Violet
import io.surprise.vpn.ui.theme.Warning

@Composable
fun LogsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var source by remember { mutableStateOf<LogSource?>(null) }
    val visible = logs.filter { source == null || it.source == source }.reversed()

    if (visible.isEmpty()) {
        EmptyLogsReference(
            modifier = modifier,
            onClear = {
                viewModel.clearDiagnostics()
                io.surprise.vpn.core.TunnelLogs.clear()
            }
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopIconButton(
                icon = Icons.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = onBack
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("SESSION LOGS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("Tunnel logs", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(logs.joinToString("\n") { "${it.time} [${it.source}] ${it.message}" }))
                viewModel.notify("Logs copied")
            }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy logs", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                viewModel.clearDiagnostics()
                // TunnelLogs is intentionally cleared through the same public surface as the screen.
                io.surprise.vpn.core.TunnelLogs.clear()
            }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear logs", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallActionChip("All", { source = null }, active = source == null)
            SmallActionChip("App", { source = LogSource.APP }, active = source == LogSource.APP)
            SmallActionChip("Xray core", { source = LogSource.CORE }, active = source == LogSource.CORE)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Newest first · ${logs.size} entries captured this session",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("No log entries yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Connect a tunnel to populate the session log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { "${it.timestamp}-${it.source}-${it.message.hashCode()}" }) { entry ->
                    val levelColor = when {
                        entry.level.contains("ERROR", ignoreCase = true) -> Danger
                        entry.level.contains("WARN", ignoreCase = true) -> Warning
                        entry.source == LogSource.CORE -> Violet
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                entry.time,
                                style = JsonTextStyle.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(levelColor)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${entry.source.name} · ${entry.level}",
                                style = MaterialTheme.typography.labelSmall,
                                color = levelColor
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                entry.message,
                                style = JsonTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLogsReference(modifier: Modifier, onClear: () -> Unit) {
    Box(modifier = modifier.fillMaxSize()) {
        IconButton(
            onClick = onClear,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp)
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear logs", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(68.dp))
            Spacer(Modifier.height(22.dp))
            Text("[  NO LOGS  ]", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 2.sp))
            Spacer(Modifier.height(10.dp))
            Text("No logs available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                "Connect the VPN to generate connection and diagnostic logs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
