package io.surprise.vpn.ui.configs

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.surprise.vpn.MainViewModel
import io.surprise.vpn.ProfilePingState
import io.surprise.vpn.data.ShareLinkParser
import io.surprise.vpn.data.model.ConnectionStatus
import io.surprise.vpn.data.model.VpnProfile
import io.surprise.vpn.data.model.VpnProtocol
import io.surprise.vpn.ui.components.ConfirmDialog
import io.surprise.vpn.ui.components.GlassCard
import io.surprise.vpn.ui.components.GradientButton
import io.surprise.vpn.ui.components.OutlineButton
import io.surprise.vpn.ui.components.ProtocolBadge
import io.surprise.vpn.ui.components.SmallActionChip
import io.surprise.vpn.ui.components.V2TextField
import io.surprise.vpn.util.shortEndpoint

@Composable
fun ConfigsScreen(
    viewModel: MainViewModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val tunnel by viewModel.tunnel.collectAsStateWithLifecycle()
    val profilePings by viewModel.profilePings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<VpnProtocol?>(null) }
    var deleteTarget by remember { mutableStateOf<VpnProfile?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.onSuccess { text ->
                if (!text.isNullOrBlank()) viewModel.importText(text)
            }.onFailure {
                viewModel.notify("Could not read that file.", isError = true)
            }
        }
    }

    fun importClipboard() {
        val text = clipboard.getText()?.text
        if (text.isNullOrBlank()) {
            viewModel.notify("Clipboard is empty. Copy a share link or Xray JSON first.", isError = true)
        } else {
            viewModel.importText(text)
        }
    }

    val visible = profiles.filter { profile ->
        val matchesProtocol = filter == null || profile.protocol == filter
        val q = query.trim()
        val matchesQuery = q.isBlank() ||
            profile.name.contains(q, ignoreCase = true) ||
            profile.server.contains(q, ignoreCase = true) ||
            profile.protocol.label.contains(q, ignoreCase = true)
        matchesProtocol && matchesQuery
    }

    if (profiles.isEmpty()) {
        EmptyConfigsReference(modifier = modifier, onCreate = onAdd, onPaste = ::importClipboard)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CONFIGURATION VAULT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(5.dp))
                    Text("Configs", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "${profiles.size} server${if (profiles.size == 1) "" else "s"} stored on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GradientButton(
                    text = "New",
                    onClick = onAdd,
                    icon = Icons.Rounded.Add,
                    fillWidth = false
                )
            }
        }

        item {
            V2TextField(
                value = query,
                onValueChange = { query = it },
                label = "Search configurations",
                placeholder = "Name, server, protocol…",
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConfigFilterChip("All", filter == null) { filter = null }
                VpnProtocol.entries.forEach { protocol ->
                    ConfigFilterChip(protocol.label, filter == protocol) { filter = protocol }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlineButton(
                    text = "Paste link / JSON",
                    onClick = ::importClipboard,
                    icon = Icons.Rounded.ContentPaste,
                    modifier = Modifier.weight(1f)
                )
                OutlineButton(
                    text = "Import file",
                    onClick = { fileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    icon = Icons.Rounded.UploadFile,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (profiles.isEmpty()) {
            item {
                EmptyVault(
                    onCreate = onAdd,
                    onPaste = ::importClipboard
                )
            }
        } else if (visible.isEmpty()) {
            item {
                GlassCard(
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(vertical = 32.dp, horizontal = 20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No matching configurations", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Try another search or filter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(visible, key = { it.id }) { profile ->
                ConfigCard(
                    profile = profile,
                    selected = selected?.id == profile.id,
                    connected = tunnel.status == ConnectionStatus.CONNECTED && tunnel.profileId == profile.id,
                    pingState = profilePings[profile.id],
                    onSelect = { viewModel.selectProfile(profile.id) },
                    onPing = { viewModel.pingProfile(profile) },
                    onEdit = { onEdit(profile.id) },
                    onDuplicate = { viewModel.duplicateProfile(profile.id) },
                    onShare = {
                        runCatching {
                            val text = ShareLinkParser.toShareLink(profile)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, profile.name)
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share ${profile.name}"))
                        }.onFailure {
                            viewModel.notify("This configuration could not be shared.", isError = true)
                        }
                    },
                    onDelete = { deleteTarget = profile }
                )
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete configuration?",
            text = "“${target.name}” will be removed from this device. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                if (tunnel.status == ConnectionStatus.CONNECTED && tunnel.profileId == target.id) {
                    viewModel.stopTunnel()
                }
                viewModel.deleteProfile(target.id)
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun EmptyConfigsReference(
    modifier: Modifier,
    onCreate: () -> Unit,
    onPaste: () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f), modifier = Modifier.size(68.dp))
            Spacer(Modifier.height(22.dp))
            Text("[  NO CONFIGS  ]", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 2.sp))
            Spacer(Modifier.height(10.dp))
            Text(
                "It looks like you have not set up any configurations yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Create a profile, paste a share link, or import a JSON configuration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCreate)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(10.dp))
                Text("Add Configuration", color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Paste link / JSON",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onPaste).padding(8.dp)
            )
        }
    }
}

@Composable
private fun ConfigFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceVariant.copy(alpha = 0.32f))
            .border(
                1.dp,
                if (selected) scheme.primary.copy(alpha = 0.45f) else scheme.outline.copy(alpha = 0.45f),
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, null, tint = scheme.primary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConfigCard(
    profile: VpnProfile,
    selected: Boolean,
    connected: Boolean,
    pingState: ProfilePingState?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    GlassCard(
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(17.dp),
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (selected) scheme.primary.copy(alpha = 0.55f) else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(24.dp)
            )
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                scheme.primary.copy(alpha = 0.16f),
                                scheme.secondary.copy(alpha = 0.12f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(scheme.primary)
                        )
                    }
                    if (connected) {
                        Spacer(Modifier.width(6.dp))
                        ProtocolBadge("LIVE")
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    shortEndpoint(profile.server, profile.port),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProtocolBadge(
                        if (profile.isJsonBacked) "Xray JSON" else profile.protocol.label,
                        protocol = profile.protocol,
                        json = profile.isJsonBacked
                    )
                    ProtocolBadge(profile.securityLabel)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallActionChip(
                        text = if (pingState?.loading == true) "Pinging…" else "Ping",
                        onClick = onPing,
                        icon = Icons.Rounded.Speed,
                        active = pingState?.result != null
                    )
                    Spacer(Modifier.width(9.dp))
                    when {
                        pingState?.loading == true -> Text(
                            "Checking TCP ${profile.port}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                        pingState?.result != null -> Text(
                            "${pingState.result.latencyMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary
                        )
                        !pingState?.error.isNullOrBlank() -> Text(
                            "Unreachable",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.error
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Configuration actions",
                        tint = scheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = {
                            menuOpen = false
                            onDuplicate()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = {
                            menuOpen = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = scheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = scheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyVault(
    onCreate: () -> Unit,
    onPaste: () -> Unit
) {
    GlassCard(
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(29.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Bring your own server", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Build a configuration with the guided form, paste a share link, or import a full Xray JSON file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            GradientButton(
                text = "Create configuration",
                onClick = onCreate,
                icon = Icons.Rounded.Add
            )
            Spacer(Modifier.height(9.dp))
            OutlineButton(
                text = "Paste link or JSON",
                onClick = onPaste,
                icon = Icons.Rounded.ContentPaste,
                fillWidth = true
            )
        }
    }
}
