package io.surprise.vpn.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.surprise.vpn.data.model.CustomPalette
import io.surprise.vpn.data.model.ThemePalette

val Aqua = Color(0xFF35E0A1)
val AquaSoft = Color(0xFF9BFFD5)
val AquaDeep = Color(0xFF0E8E68)
val Violet = Color(0xFF8B7CFF)
val VioletSoft = Color(0xFFC7C0FF)
val Coral = Color(0xFFFF8B70)
val CoralDeep = Color(0xFFC4513E)
val Ice = Color(0xFFF2F6F4)
val IceMuted = Color(0xFFA2AEA9)
val Ink = Color(0xFF080B0E)
val InkSurface = Color(0xFF101519)
val InkRaised = Color(0xFF171E22)
val InkOutline = Color(0xFF2A3538)
val Success = Color(0xFF35E0A1)
val Warning = Color(0xFFFFC466)
val Danger = Color(0xFFFF6B70)
val LightBackground = Color(0xFFF4F8FA)
val LightSurface = Color(0xFFFFFFFF)
val LightRaised = Color(0xFFE6EFF4)
val LightInk = Color(0xFF07131E)
val LightMuted = Color(0xFF526675)
val LightOutline = Color(0xFFC8D7E0)

val AquaViolet = Brush.linearGradient(listOf(Aqua, Violet))
val AquaVioletSoft = Brush.linearGradient(
    listOf(Aqua.copy(alpha = 0.22f), Violet.copy(alpha = 0.16f), Color.Transparent)
)
val CoralViolet = Brush.linearGradient(listOf(Coral, Violet))
val BrandSweep = Brush.sweepGradient(listOf(Aqua, Color(0xFF65D9FF), Violet, Coral, Aqua))

data class PaletteVisual(
    val primary: Color,
    val secondary: Color,
    val success: Color,
    val lightCanvas: Color,
    val darkCanvas: Color,
    val lightSurface: Color,
    val darkSurface: Color,
    val alert: Color
)

fun paletteVisual(palette: ThemePalette, custom: CustomPalette = CustomPalette()): PaletteVisual = when (palette) {
    ThemePalette.CYBER_TRUST -> PaletteVisual(
        primary = Color(0xFF2563EB), secondary = Color(0xFF10B981), success = Color(0xFF10B981),
        lightCanvas = Color(0xFFF8FAFC), darkCanvas = Color(0xFF0F172A),
        lightSurface = Color(0xFFFFFFFF), darkSurface = Color(0xFF17233A), alert = Color(0xFFF43F5E)
    )
    ThemePalette.STEALTH_SPEED -> PaletteVisual(
        primary = Color(0xFF6366F1), secondary = Color(0xFF06B6D4), success = Color(0xFF06B6D4),
        lightCanvas = Color(0xFFF4F4F5), darkCanvas = Color(0xFF121214),
        lightSurface = Color(0xFFFFFFFF), darkSurface = Color(0xFF1E1E22), alert = Color(0xFFF97316)
    )
    ThemePalette.MINIMALIST -> PaletteVisual(
        primary = Color(0xFF0D9488), secondary = Color(0xFF14B8A6), success = Color(0xFF0D9488),
        lightCanvas = Color(0xFFFFFFFF), darkCanvas = Color(0xFF09090B),
        lightSurface = Color(0xFFF1F5F9), darkSurface = Color(0xFF18181B), alert = Color(0xFFF43F5E)
    )
    ThemePalette.OCEANIC -> PaletteVisual(
        primary = Color(0xFF0284C7), secondary = Color(0xFF06B6D4), success = Color(0xFF14B8A6),
        lightCanvas = Color(0xFFF0F9FF), darkCanvas = Color(0xFF082F49),
        lightSurface = Color(0xFFFFFFFF), darkSurface = Color(0xFF0C405F), alert = Color(0xFFF97316)
    )
    ThemePalette.FOREST -> PaletteVisual(
        primary = Color(0xFF15803D), secondary = Color(0xFF84CC16), success = Color(0xFF22C55E),
        lightCanvas = Color(0xFFF0FDF4), darkCanvas = Color(0xFF102A1B),
        lightSurface = Color(0xFFFFFFFF), darkSurface = Color(0xFF193D27), alert = Color(0xFFEF4444)
    )
    ThemePalette.SUNSET -> PaletteVisual(
        primary = Color(0xFF7C3AED), secondary = Color(0xFFF59E0B), success = Color(0xFFF59E0B),
        lightCanvas = Color(0xFFFFFBEB), darkCanvas = Color(0xFF241437),
        lightSurface = Color(0xFFFFFFFF), darkSurface = Color(0xFF35204B), alert = Color(0xFFEF4444)
    )
    ThemePalette.MONOCHROME -> PaletteVisual(
        primary = Color(0xFFE5E7EB), secondary = Color(0xFF9CA3AF), success = Color(0xFFF9FAFB),
        lightCanvas = Color(0xFFF9FAFB), darkCanvas = Color(0xFF111111),
        lightSurface = Color(0xFFFFFFFF), darkSurface = Color(0xFF1F1F1F), alert = Color(0xFFF87171)
    )
    ThemePalette.CUSTOM -> PaletteVisual(
        primary = custom.primary.toComposeColor(Color(0xFF2563EB)),
        secondary = custom.secondary.toComposeColor(Color(0xFF10B981)),
        success = custom.success.toComposeColor(Color(0xFF10B981)),
        lightCanvas = custom.lightCanvas.toComposeColor(Color(0xFFF8FAFC)),
        darkCanvas = custom.darkCanvas.toComposeColor(Color(0xFF0F172A)),
        lightSurface = Color.White,
        darkSurface = custom.darkCanvas.toComposeColor(Color(0xFF0F172A)).lighten(0.08f),
        alert = custom.alert.toComposeColor(Color(0xFFF43F5E))
    )
}

private fun String.toComposeColor(fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(trim().let { if (it.startsWith("#")) it else "#$it" }))
}.getOrDefault(fallback)

private fun Color.lighten(amount: Float): Color = Color(
    red = (red + amount).coerceAtMost(1f),
    green = (green + amount).coerceAtMost(1f),
    blue = (blue + amount).coerceAtMost(1f),
    alpha = alpha
)
