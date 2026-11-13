package io.surprise.vpn.ui.navigation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.surprise.vpn.MainViewModel
import io.surprise.vpn.data.model.ConnectionStatus
import io.surprise.vpn.ui.components.AuroraBackground
import io.surprise.vpn.ui.components.V2TunBottomBar
import io.surprise.vpn.ui.configs.ConfigEditorScreen
import io.surprise.vpn.ui.configs.ConfigsScreen
import io.surprise.vpn.ui.dashboard.NpvDashboardScreen
import io.surprise.vpn.ui.logs.LogsScreen
import io.surprise.vpn.ui.settings.SettingsScreen
import io.surprise.vpn.ui.tools.ToolsScreen

private val topLevelRoutes = setOf("dashboard", "configs", "logs", "settings")

@Composable
fun V2TunApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarIsError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val tunnel by viewModel.tunnel.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in topLevelRoutes

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarIsError = message.isError
            snackbarHostState.showSnackbar(message.text)
        }
    }

    fun navigateTopLevel(route: String) {
        if (route == currentRoute) return
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startTunnel()
        } else {
            viewModel.notify("VPN permission is required to create the tunnel.", isError = true)
        }
    }

    fun beginVpnFlow() {
        val profile = selected
        if (profile == null) {
            navController.navigate("editor")
            return
        }
        runCatching { VpnService.prepare(context) }
            .onSuccess { permissionIntent ->
                if (permissionIntent == null) viewModel.startTunnel() else vpnPermissionLauncher.launch(permissionIntent)
            }
            .onFailure { viewModel.notify("Android could not prepare the VPN service.", isError = true) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { beginVpnFlow() }

    fun requestConnection() {
        when (tunnel.status) {
            ConnectionStatus.CONNECTED -> viewModel.stopTunnel()
            ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTING -> Unit
            else -> {
                if (selected == null) {
                    navController.navigate("editor")
                } else if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    beginVpnFlow()
                }
            }
        }
    }

    AuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBottomBar) {
                    V2TunBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = ::navigateTopLevel
                    )
                }
            },
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        containerColor = if (snackbarIsError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (snackbarIsError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding()
                    .then(if (showBottomBar) Modifier else Modifier.navigationBarsPadding())
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "dashboard",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 18 }
                    },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = {
                        fadeIn(tween(200)) + slideInHorizontally(tween(240)) { -it / 18 }
                    },
                    popExitTransition = {
                        fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { it / 18 }
                    }
                ) {
                    composable("dashboard") {
                        NpvDashboardScreen(
                            viewModel = viewModel,
                            onSettings = { navigateTopLevel("settings") },
                            onOpenConfigs = { navigateTopLevel("configs") },
                            onOpenEditor = { id ->
                                if (id == null) navController.navigate("editor")
                                else navController.navigate("editor?profileId=${Uri.encode(id)}")
                            },
                            onConnectRequested = ::requestConnection,
                            onDisconnectRequested = { viewModel.stopTunnel() }
                        )
                    }
                    composable("configs") {
                        ConfigsScreen(
                            viewModel = viewModel,
                            onAdd = { navController.navigate("editor") },
                            onEdit = { id -> navController.navigate("editor?profileId=${Uri.encode(id)}") }
                        )
                    }
                    composable("tools") {
                        ToolsScreen(
                            viewModel = viewModel,
                            onOpenLogs = { navController.navigate("logs") },
                            onOpenSettings = { navigateTopLevel("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = viewModel,
                            onOpenLogs = { navController.navigate("logs") }
                        )
                    }
                    composable("logs") {
                        LogsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "editor?profileId={profileId}",
                        arguments = listOf(
                            navArgument("profileId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { entry ->
                        ConfigEditorScreen(
                            viewModel = viewModel,
                            profileId = entry.arguments?.getString("profileId"),
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
