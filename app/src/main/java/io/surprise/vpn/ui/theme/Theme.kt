package io.surprise.vpn.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.surprise.vpn.data.model.CustomPalette
import io.surprise.vpn.data.model.ThemeMode
import io.surprise.vpn.data.model.ThemePalette

private val ColorOnAqua = Color(0xFF002117)
private val ColorOnViolet = Color(0xFF160C52)
private val ColorOnCoral = Color(0xFF3D0716)

private val DarkColors = darkColorScheme(
    primary = Aqua,
    onPrimary = ColorOnAqua,
    primaryContainer = Color(0xFF0D3B2D),
    onPrimaryContainer = AquaSoft,
    secondary = Violet,
    onSecondary = ColorOnViolet,
    secondaryContainer = Color(0xFF29254B),
    onSecondaryContainer = VioletSoft,
    tertiary = Coral,
    onTertiary = ColorOnCoral,
    tertiaryContainer = Color(0xFF4A261F),
    onTertiaryContainer = Color(0xFFFFD8DF),
    background = Ink,
    onBackground = Ice,
    surface = InkSurface,
    onSurface = Ice,
    surfaceVariant = InkRaised,
    onSurfaceVariant = IceMuted,
    outline = InkOutline,
    outlineVariant = Color(0xFF202A2D),
    error = Danger,
    onError = Color(0xFF2B0008)
)

private fun paletteDarkColors(palette: ThemePalette, custom: CustomPalette) = paletteVisual(palette, custom).let { p -> darkColorScheme(
    primary = p.primary, onPrimary = Color.White, primaryContainer = p.primary.copy(alpha = 0.30f),
    onPrimaryContainer = Color.White, secondary = p.secondary, onSecondary = Color.White,
    secondaryContainer = p.secondary.copy(alpha = 0.25f), onSecondaryContainer = Color.White,
    tertiary = p.success, onTertiary = Color.White, tertiaryContainer = p.success.copy(alpha = 0.22f),
    onTertiaryContainer = Color.White, background = p.darkCanvas, onBackground = Color(0xFFE5E7EB),
    surface = p.darkSurface, onSurface = Color(0xFFE5E7EB), surfaceVariant = p.darkSurface.copy(alpha = 0.92f),
    onSurfaceVariant = Color(0xFFB5C0D0), outline = Color(0xFF41516A), outlineVariant = Color(0xFF27344A),
    error = p.alert, onError = Color.White
)}

private val LightColors = lightColorScheme(
    primary = Color(0xFF007A61),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5F2DD),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF5A4BD6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E0FF),
    onSecondaryContainer = Color(0xFF160C52),
    tertiary = CoralDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E0),
    onTertiaryContainer = Color(0xFF3D0716),
    background = LightBackground,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightRaised,
    onSurfaceVariant = LightMuted,
    outline = LightOutline,
    outlineVariant = Color(0xFFE2EBF0),
    error = Color(0xFFB3261E),
    onError = Color.White
)

private fun paletteLightColors(palette: ThemePalette, custom: CustomPalette) = paletteVisual(palette, custom).let { p -> lightColorScheme(
    primary = p.primary, onPrimary = Color.White, primaryContainer = p.primary.copy(alpha = 0.15f),
    onPrimaryContainer = p.primary, secondary = p.secondary, onSecondary = Color.White,
    secondaryContainer = p.secondary.copy(alpha = 0.15f), onSecondaryContainer = p.secondary,
    tertiary = p.success, onTertiary = Color.White, tertiaryContainer = p.success.copy(alpha = 0.15f),
    onTertiaryContainer = p.success, background = p.lightCanvas, onBackground = Color(0xFF111827),
    surface = p.lightSurface, onSurface = Color(0xFF111827), surfaceVariant = Color(0xFFE8EDF3),
    onSurfaceVariant = Color(0xFF526071), outline = Color(0xFFCBD5E1), outlineVariant = Color(0xFFE2E8F0),
    error = p.alert, onError = Color.White
)}

@Composable
fun V2TunTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    themePalette: ThemePalette = ThemePalette.CYBER_TRUST,
    customPalette: CustomPalette = CustomPalette(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> paletteDarkColors(themePalette, customPalette)
        else -> paletteLightColors(themePalette, customPalette)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = V2TunTypography,
        shapes = V2TunShapes,
        content = content
    )
}
