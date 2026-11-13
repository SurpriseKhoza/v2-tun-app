package io.surprise.vpn

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.surprise.vpn.ui.navigation.V2TunApp
import io.surprise.vpn.ui.splash.V2TunSplashScreen
import io.surprise.vpn.ui.theme.V2TunTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            var showSplash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(1_250)
                showSplash = false
            }
            V2TunTheme(
                themeMode = settings.themeMode,
                themePalette = settings.themePalette,
                customPalette = settings.customPalette,
                dynamicColor = settings.dynamicColor
            ) {
                AnimatedVisibility(
                    visible = !showSplash,
                    enter = fadeIn(animationSpec = tween(500)),
                    exit = fadeOut(animationSpec = tween(250))
                ) {
                    V2TunApp(viewModel = viewModel)
                }
                AnimatedVisibility(
                    visible = showSplash,
                    enter = fadeIn(animationSpec = tween(250)),
                    exit = fadeOut(animationSpec = tween(450))
                ) {
                    V2TunSplashScreen()
                }
            }
        }
    }
}
