package io.surprise.vpn.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.surprise.vpn.BuildConfig
import io.surprise.vpn.MainViewModel
import io.surprise.vpn.core.XrayEngine
import io.surprise.vpn.data.model.LogLevel
import io.surprise.vpn.data.model.AppRoutingMode
import io.surprise.vpn.data.model.ThemeMode
import io.surprise.vpn.data.model.ThemePalette
import io.surprise.vpn.ui.components.SoftSurface
import io.surprise.vpn.ui.components.BrandMark
import io.surprise.vpn.ui.components.SectionHeader
import io.surprise.vpn.ui.components.V2Dropdown
import io.surprise.vpn.ui.components.V2SegmentedControl
import io.surprise.vpn.ui.components.V2SliderRow
import io.surprise.vpn.ui.components.V2SwitchRow
import io.surprise.vpn.ui.components.V2TextField
import io.surprise.vpn.ui.theme.JsonTextStyle
import io.surprise.vpn.ui.theme.paletteVisual
import kotlinx.coroutines.delay

private val PlainFieldKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false
)

private val HexColor = Regex("^#[0-9a-fA-F]{6}$")
private val Ipv6Literal = Regex("^[0-9a-fA-F:]+$")

/** Blank and “local” follow the active network; otherwise an IP literal or a URL-style server. */
private fun isValidDnsServer(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty() || value.equals("local", ignoreCase = true)) return true
    if (value.any { it.isWhitespace() }) return false
    if (value.contains("://")) return value.substringAfter("://").isNotBlank()
    val octets = value.split('.')
    if (octets.size == 4 && octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) return true
    return value.count { it == ':' } >= 2 && Ipv6Literal.matches(value)
}

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onOpenLogs: () -> Unit,
    onOpenAppPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tunnel by viewModel.tunnel.collectAsStateWithLifecycle()
    var showLicenses by remember { mutableStateOf(false) }
    var showTileHelp by remember { mutableStateOf(false) }

    fun openSystem(intent: Intent) {
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            viewModel.notify("This settings page is not available on this device.", isError = true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("CONTROL ROOM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(5.dp))
                Text("Settings", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Tune the tunnel to your network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BrandMark(size = 38.dp)
        }

        SectionHeader(title = "Tunnel", eyebrow = "CONNECTION")

        SoftSurface(
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(17.dp)
        ) {
            V2SwitchRow(
                title = "Auto-connect after reboot",
                description = "Android may still ask for VPN permission the first time.",
                checked = settings.autoConnectOnBoot,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(autoConnectOnBoot = enabled) } }
            )
            Spacer(Modifier.height(14.dp))
            V2SwitchRow(
                title = "Live notification updates",
                description = "The required VPN status notification always remains visible.",
                checked = settings.notificationsEnabled,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(notificationsEnabled = enabled) } }
            )
            Spacer(Modifier.height(16.dp))
            Text("DNS servers", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                DebouncedField(
                    label = "Primary",
                    value = settings.dnsPrimary,
                    modifier = Modifier.weight(1f),
                    isValid = ::isValidDnsServer,
                    errorText = "Use an IP, a URL such as tcp://1.1.1.1, or ‘local’."
                ) { value -> viewModel.updateSettings { it.copy(dnsPrimary = value) } }
                DebouncedField(
                    label = "Secondary",
                    value = settings.dnsSecondary,
                    modifier = Modifier.weight(1f),
                    isValid = ::isValidDnsServer,
                    errorText = "Use an IP, a URL such as tcp://1.1.1.1, or ‘local’."
                ) { value -> viewModel.updateSettings { it.copy(dnsSecondary = value) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Use ‘local’ to follow the active network DNS. Enter IP addresses to override it explicitly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            V2SliderRow(
                title = "Tunnel MTU",
                valueText = "${settings.mtu}",
                value = settings.mtu.toFloat(),
                onValueChange = { value -> viewModel.updateSettings { it.copy(mtu = value.toInt()) } },
                valueRange = 1200f..1500f,
                steps = 5
            )
            V2SwitchRow(
                title = "Enable IPv6",
                description = "Adds an IPv6 interface and route. Leave off if your carrier has unstable IPv6.",
                checked = settings.ipv6Enabled,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(ipv6Enabled = enabled) } }
            )
            Spacer(Modifier.height(12.dp))
            V2Dropdown(
                selected = settings.logLevel,
                options = LogLevel.entries,
                label = "Xray log level",
                labelFor = { it.label },
                onSelect = { level -> viewModel.updateSettings { it.copy(logLevel = level) } }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Changes apply to the next connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionHeader(title = "App routing", eyebrow = "SCOPE")

        SoftSurface(
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(17.dp)
        ) {
            V2Dropdown(
                selected = settings.appRoutingMode,
                options = AppRoutingMode.entries,
                label = "Traffic scope",
                labelFor = { mode ->
                    when (mode) {
                        AppRoutingMode.ALL -> "All apps"
                        AppRoutingMode.ALLOWLIST -> "Only selected apps"
                        AppRoutingMode.BLOCKLIST -> "All except selected apps"
                    }
                },
                onSelect = { mode -> viewModel.updateSettings { it.copy(appRoutingMode = mode) } }
            )
            AnimatedVisibility(visible = settings.appRoutingMode != AppRoutingMode.ALL) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    val count = settings.appRoutingPackages.size
                    SettingActionRow(
                        icon = Icons.Rounded.Apps,
                        title = "Choose apps",
                        subtitle = when (count) {
                            0 -> "No apps selected yet"
                            1 -> "1 app selected"
                            else -> "$count apps selected"
                        },
                        onClick = onOpenAppPicker,
                        showDivider = false,
                        compact = true
                    )
                    if (settings.appRoutingMode == AppRoutingMode.ALLOWLIST && count == 0) {
                        Text(
                            "Pick at least one app, or the tunnel will refuse to start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                "Changes apply on the next connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            V2SwitchRow(
                title = "Automatic reconnect",
                description = "Reconnect after a network handover or transient tunnel failure.",
                checked = settings.autoReconnect,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(autoReconnect = enabled) } }
            )
            AnimatedVisibility(visible = settings.autoReconnect) {
                val attempts = settings.reconnectAttempts.coerceIn(1, 10)
                V2SliderRow(
                    title = "Reconnect attempts",
                    valueText = attempts.toString(),
                    value = attempts.toFloat(),
                    onValueChange = { value -> viewModel.updateSettings { it.copy(reconnectAttempts = value.toInt()) } },
                    valueRange = 1f..10f,
                    steps = 8
                )
            }
        }

        SectionHeader(title = "Appearance", eyebrow = "LOOK & FEEL")

        SoftSurface(
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(17.dp)
        ) {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            V2SegmentedControl(
                options = listOf("System", "Dark", "Light"),
                selectedIndex = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> 0
                    ThemeMode.DARK -> 1
                    ThemeMode.LIGHT -> 2
                },
                onSelect = { index ->
                    viewModel.updateSettings {
                        it.copy(
                            themeMode = when (index) {
                                0 -> ThemeMode.SYSTEM
                                1 -> ThemeMode.DARK
                                else -> ThemeMode.LIGHT
                            }
                        )
                    }
                }
            )
            Spacer(Modifier.height(14.dp))
            Text("Color palette", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Choose the visual identity used across V2-TUN. Cyber Trust is the default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePalette.entries.forEach { palette ->
                    val visual = paletteVisual(palette)
                    val selected = settings.themePalette == palette
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(15.dp)
                            )
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { viewModel.updateSettings { it.copy(themePalette = palette) } }
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(visual.primary))
                            Box(Modifier.size(12.dp).clip(CircleShape).background(visual.secondary))
                            Box(Modifier.size(12.dp).clip(CircleShape).background(visual.alert))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(palette.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                palette.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (selected) {
                            Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (settings.themePalette == ThemePalette.CUSTOM) {
                Spacer(Modifier.height(12.dp))
                Text("Custom colors", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Enter six-digit hex values such as #2563EB. Valid colors apply instantly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(9.dp))
                CustomColorField("Primary brand", settings.customPalette.primary) { value ->
                    viewModel.updateSettings { it.copy(customPalette = it.customPalette.copy(primary = value)) }
                }
                CustomColorField("Secondary accent", settings.customPalette.secondary) { value ->
                    viewModel.updateSettings { it.copy(customPalette = it.customPalette.copy(secondary = value)) }
                }
                CustomColorField("Connected / success", settings.customPalette.success) { value ->
                    viewModel.updateSettings { it.copy(customPalette = it.customPalette.copy(success = value)) }
                }
                CustomColorField("Alert", settings.customPalette.alert) { value ->
                    viewModel.updateSettings { it.copy(customPalette = it.customPalette.copy(alert = value)) }
                }
                CustomColorField("Light canvas", settings.customPalette.lightCanvas) { value ->
                    viewModel.updateSettings { it.copy(customPalette = it.customPalette.copy(lightCanvas = value)) }
                }
                CustomColorField("Dark canvas", settings.customPalette.darkCanvas) { value ->
                    viewModel.updateSettings { it.copy(customPalette = it.customPalette.copy(darkCanvas = value)) }
                }
            }
            Spacer(Modifier.height(14.dp))
            val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            V2SwitchRow(
                title = "Dynamic color",
                description = if (dynamicColorSupported) {
                    "Use the device palette for surfaces and accents."
                } else {
                    "Available on Android 12 and newer."
                },
                checked = settings.dynamicColor && dynamicColorSupported,
                enabled = dynamicColorSupported,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(dynamicColor = enabled) } }
            )
            Spacer(Modifier.height(12.dp))
            V2SwitchRow(
                title = "Haptic feedback",
                description = "A subtle confirmation for the main connect control.",
                checked = settings.hapticsEnabled,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(hapticsEnabled = enabled) } }
            )
        }

        SectionHeader(title = "Android integration", eyebrow = "SYSTEM")

        SoftSurface(
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 14.dp)
        ) {
            SettingActionRow(
                icon = Icons.Rounded.Security,
                title = "Always-on VPN",
                subtitle = "Configure Android's automatic reconnect and kill switch.",
                onClick = { openSystem(Intent(Settings.ACTION_VPN_SETTINGS)) }
            )
            SettingActionRow(
                icon = Icons.Rounded.BatterySaver,
                title = "Battery optimization",
                subtitle = "Allow the tunnel service to stay alive in the background.",
                onClick = { openSystem(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            )
            SettingActionRow(
                icon = Icons.Rounded.NotificationsActive,
                title = "Notification controls",
                subtitle = "Manage V2-TUN's persistent connection notification.",
                onClick = {
                    openSystem(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    })
                }
            )
            SettingActionRow(
                icon = Icons.Rounded.WifiTethering,
                title = "Quick Settings tile",
                subtitle = "See how to add V2-TUN to your Quick Settings panel.",
                // There is no system settings page for this, so show the steps instead.
                onClick = { showTileHelp = true }
            )
            SettingActionRow(
                icon = Icons.Rounded.Terminal,
                title = "Tunnel logs",
                subtitle = "Inspect app events and Xray core errors from this session.",
                onClick = onOpenLogs,
                showDivider = false
            )
        }

        SectionHeader(title = "Privacy", eyebrow = "LOCAL BY DESIGN")

        SoftSurface(
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(17.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("No account. No analytics. No server list.", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your configurations are stored in the app's private files directory. V2-TUN does not operate a remote account service or collect browsing history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SectionHeader(title = "About", eyebrow = "BUILD")

        SoftSurface(
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(17.dp)
        ) {
            AboutRow("Application", "V2-TUN ${BuildConfig.VERSION_NAME}")
            AboutRow("Package", "io.surprise.vpn")
            AboutRow("Xray core", XrayEngine.CORE_VERSION)
            AboutRow("Build type", BuildConfig.BUILD_TYPE)
            Spacer(Modifier.height(10.dp))
            Text(
                "V2-TUN is a client only. It does not provide, sell, or operate VPN servers. Use configurations from providers or administrators you trust and have permission to access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            SettingActionRow(
                icon = Icons.Rounded.Description,
                title = "Open-source notices",
                subtitle = "libXray, Xray-core, Manrope, and JetBrains Mono licenses.",
                onClick = { showLicenses = true },
                showDivider = false,
                compact = true
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                "Core status: ${if (tunnel.isActive) "active" else "idle"} · ${Build.VERSION.RELEASE}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
        }
        Spacer(Modifier.height(10.dp))
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
    if (showTileHelp) {
        TileHelpDialog(onDismiss = { showTileHelp = false })
    }
}

/**
 * A text field that keeps what you type locally, shows an inline error for invalid input, and
 * only writes a valid value to storage after you stop typing (instead of on every keystroke).
 */
@Composable
private fun DebouncedField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isValid: (String) -> Boolean,
    errorText: String,
    onCommit: (String) -> Unit
) {
    var text by remember { mutableStateOf(value) }
    // Pick up changes made elsewhere (for example a restored backup).
    LaunchedEffect(value) {
        if (value != text.trim()) text = value
    }
    val valid = isValid(text)
    LaunchedEffect(text) {
        if (valid && text.trim() != value) {
            delay(400)
            onCommit(text.trim())
        }
    }
    V2TextField(
        value = text,
        onValueChange = { text = it },
        label = label,
        modifier = modifier,
        isError = !valid,
        supportingText = if (valid) null else errorText,
        keyboardOptions = PlainFieldKeyboard
    )
}

@Composable
private fun CustomColorField(label: String, value: String, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != text.trim()) text = value
    }
    val valid = HexColor.matches(text.trim())
    LaunchedEffect(text) {
        if (valid && text.trim() != value) {
            delay(400)
            onCommit(text.trim())
        }
    }
    val swatch = if (valid) {
        runCatching { Color(android.graphics.Color.parseColor(text.trim())) }
            .getOrElse { MaterialTheme.colorScheme.primary }
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 18.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(swatch)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), CircleShape)
        )
        Spacer(Modifier.width(9.dp))
        V2TextField(
            value = text,
            onValueChange = { text = it },
            label = label,
            modifier = Modifier.weight(1f),
            isError = !valid,
            supportingText = if (valid) null else "Use six hex digits, like #2563EB.",
            keyboardOptions = PlainFieldKeyboard
        )
    }
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun SettingActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    compact: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = if (compact) 0.dp else 6.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Rounded.ArrowForward,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(start = 52.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TileHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text("Add the Quick Settings tile", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                "1. Swipe down twice from the top of the screen to open Quick Settings.\n" +
                    "2. Tap the pencil (Edit) icon.\n" +
                    "3. Drag the V2-TUN tile into your active tiles.\n\n" +
                    "The exact wording differs between devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it", color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        runCatching {
            val files = listOf(
                "licenses/libXray-MIT.txt" to "libXray (MIT)",
                "licenses/Xray-core-MPL-2.0.txt" to "Xray-core (MPL-2.0)",
                "licenses/Manrope-OFL.txt" to "Manrope (SIL OFL)",
                "licenses/JetBrainsMono-OFL.txt" to "JetBrains Mono (SIL OFL)"
            )
            files.joinToString("\n\n") { (path, title) ->
                "== $title ==\n" + context.assets.open(path).bufferedReader().use { it.readText() }
            }
        }.getOrDefault("License notices are bundled in the app's assets.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text("Open-source notices", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text,
                    style = JsonTextStyle.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}
