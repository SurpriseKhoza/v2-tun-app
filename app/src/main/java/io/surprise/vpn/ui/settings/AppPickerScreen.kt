package io.surprise.vpn.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.surprise.vpn.MainViewModel
import io.surprise.vpn.data.model.AppRoutingMode
import io.surprise.vpn.ui.components.SmallActionChip
import io.surprise.vpn.ui.components.TopIconButton
import io.surprise.vpn.ui.components.V2TextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val installed: Boolean
)

private enum class AppFilter { ALL, SELECTED, USER, SYSTEM }

private object IconCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(160)
    fun get(packageName: String): ImageBitmap? = cache.get(packageName)
    fun put(packageName: String, bitmap: ImageBitmap) {
        cache.put(packageName, bitmap)
    }
}

private fun isSystemApp(info: ApplicationInfo): Boolean =
    (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
        (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

/**
 * Every app that has a launcher entry (the manifest declares the matching <queries> intent),
 * plus anything already selected that is no longer visible, so a stale choice can still be
 * unticked. This app itself is excluded: its own sockets are always protected.
 */
private suspend fun loadInstalledApps(
    context: Context,
    ownPackage: String,
    keep: Set<String>
): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val result = LinkedHashMap<String, InstalledApp>()
    pm.queryIntentActivities(launcher, 0).forEach { info ->
        val app = info.activityInfo?.applicationInfo ?: return@forEach
        val pkg = app.packageName
        if (pkg == ownPackage || result.containsKey(pkg)) return@forEach
        result[pkg] = InstalledApp(
            packageName = pkg,
            label = info.loadLabel(pm).toString().ifBlank { pkg },
            isSystem = isSystemApp(app),
            installed = true
        )
    }
    keep.forEach { pkg ->
        if (pkg == ownPackage || result.containsKey(pkg)) return@forEach
        val app = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        result[pkg] = if (app != null) {
            InstalledApp(pkg, app.loadLabel(pm).toString().ifBlank { pkg }, isSystemApp(app), true)
        } else {
            InstalledApp(pkg, pkg, false, false)
        }
    }
    result.values.sortedBy { it.label.lowercase(Locale.getDefault()) }
}

@Composable
fun AppPickerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selected = settings.appRoutingPackages
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.ALL) }

    LaunchedEffect(Unit) {
        apps = loadInstalledApps(
            context = context,
            ownPackage = context.packageName,
            keep = viewModel.settings.value.appRoutingPackages
        )
    }

    val visible = remember(apps, query, filter, selected) {
        val q = query.trim()
        apps.orEmpty().filter { app ->
            val matchesQuery = q.isEmpty() ||
                app.label.contains(q, ignoreCase = true) ||
                app.packageName.contains(q, ignoreCase = true)
            val matchesFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.SELECTED -> app.packageName in selected
                AppFilter.USER -> !app.isSystem
                AppFilter.SYSTEM -> app.isSystem
            }
            matchesQuery && matchesFilter
        }
    }

    fun updateSelection(transform: (Set<String>) -> Set<String>) {
        viewModel.updateSettings { it.copy(appRoutingPackages = transform(it.appRoutingPackages)) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TopIconButton(
                icon = Icons.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = onBack
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("APP ROUTING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("Choose apps", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "${selected.size} selected",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(8.dp))
        when (settings.appRoutingMode) {
            AppRoutingMode.ALL -> {
                Text(
                    "Routing is set to “All apps”, so this list has no effect yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallActionChip("Only selected apps", {
                        viewModel.updateSettings { it.copy(appRoutingMode = AppRoutingMode.ALLOWLIST) }
                    })
                    SmallActionChip("All except selected", {
                        viewModel.updateSettings { it.copy(appRoutingMode = AppRoutingMode.BLOCKLIST) }
                    })
                }
            }
            AppRoutingMode.ALLOWLIST -> Text(
                "Only the apps you tick go through the VPN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppRoutingMode.BLOCKLIST -> Text(
                "The apps you tick bypass the VPN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (settings.appRoutingMode == AppRoutingMode.ALLOWLIST && selected.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Tick at least one app, or the tunnel will refuse to start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(12.dp))
        V2TextField(
            value = query,
            onValueChange = { query = it },
            label = "Search apps",
            placeholder = "Name or package",
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false
            ),
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            },
            trailing = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else null
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallActionChip("All", { filter = AppFilter.ALL }, active = filter == AppFilter.ALL)
            SmallActionChip("Selected (${selected.size})", { filter = AppFilter.SELECTED }, active = filter == AppFilter.SELECTED)
            SmallActionChip("User apps", { filter = AppFilter.USER }, active = filter == AppFilter.USER)
            SmallActionChip("System apps", { filter = AppFilter.SYSTEM }, active = filter == AppFilter.SYSTEM)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallActionChip(
                "Select shown",
                { updateSelection { current -> current + visible.map { it.packageName } } },
                icon = Icons.Rounded.Check
            )
            SmallActionChip("Clear all", { updateSelection { emptySet() } })
        }
        Spacer(Modifier.height(8.dp))

        val loaded = apps
        when {
            loaded == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            visible.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (loaded.isEmpty()) "No launchable apps were found on this device." else "No apps match.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = app.packageName in selected,
                        onToggle = { checked ->
                            updateSelection { current ->
                                if (checked) current + app.packageName else current - app.packageName
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app.packageName, Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (app.installed) app.packageName else "${app.packageName} · not installed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState(initialValue = IconCache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap(96, 96)
                        .asImageBitmap()
                }.getOrNull()
            }?.also { IconCache.put(packageName, it) }
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.clip(RoundedCornerShape(10.dp)))
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        )
    }
}
