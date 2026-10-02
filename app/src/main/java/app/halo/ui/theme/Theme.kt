package app.halo.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.halo.data.ThemeStyle
import app.halo.tuya.BulbMode
import app.halo.tuya.BulbState

@Immutable
data class HaloColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color = Color(0xFFFF6B6B),
    val good: Color = Color(0xFF5BE49B),
)

val LocalHalo = staticCompositionLocalOf { palette(ThemeStyle.AMOLED, Color(0xFFFFB547)) }

fun palette(style: ThemeStyle, accent: Color): HaloColors {
    val onAccent = if (accent.luminance() > 0.45f) Color(0xFF15120D) else Color.White
    return when (style) {
        ThemeStyle.AMOLED -> HaloColors(Color(0xFF000000), Color(0xFF0E0E11), Color(0xFF18181D), Color(0xFF26262D),
            Color(0xFFF5F3EE), Color(0xFF9A97A0), Color(0xFF5E5C64), accent, onAccent)
        ThemeStyle.MIDNIGHT -> HaloColors(Color(0xFF05070F), Color(0xFF0D1222), Color(0xFF151C32), Color(0xFF222B47),
            Color(0xFFEFF2FA), Color(0xFF8F98B3), Color(0xFF55607D), accent, onAccent)
        ThemeStyle.GRAPHITE -> HaloColors(Color(0xFF111113), Color(0xFF1B1B1E), Color(0xFF242428), Color(0xFF323237),
            Color(0xFFF2F2F2), Color(0xFF9C9CA3), Color(0xFF65656C), accent, onAccent)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

val AccentChoices = listOf(
    0xFFFFB547, 0xFFFF7A59, 0xFFFF5E8A, 0xFFC77DFF, 0xFF7C8CFF, 0xFF4CC9F0, 0xFF3DDC97, 0xFFE8E4DA,
)

/** What the bulb's light looks like on screen. */
fun BulbState.displayColor(): Color = when (mode) {
    BulbMode.COLOUR -> Color.hsv(((hue % 360f) + 360f) % 360f, saturation.coerceIn(0f, 1f) * 0.92f + 0.03f, 1f)
    else -> kelvinColor(warmth)
}

/** 0 = 2700K warm, 1 = 6500K cool. */
fun kelvinColor(warmth: Float): Color {
    val warm = Color(0xFFFFA04D)
    val mid = Color(0xFFFFE6C4)
    val cool = Color(0xFFDDE8FF)
    return if (warmth < 0.5f) lerp(warm, mid, warmth * 2f) else lerp(mid, cool, (warmth - 0.5f) * 2f)
}

fun kelvin(warmth: Float): Int = (2700 + warmth.coerceIn(0f, 1f) * 3800).toInt() / 50 * 50

private val HaloType = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 64.sp, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 44.sp, letterSpacing = (-1.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.2.sp),
)

@Composable
fun HaloTheme(style: ThemeStyle, accent: Color, content: @Composable () -> Unit) {
    val animatedAccent by animateColorAsState(accent, tween(700), label = "accent")
    val colors = palette(style, animatedAccent)
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        secondary = colors.accent,
        background = colors.background,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.surfaceHigh,
        onSurfaceVariant = colors.textDim,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surfaceHigh,
        surfaceContainerHighest = colors.surfaceHigh,
        surfaceContainerLow = colors.surface,
        outline = colors.outline,
        outlineVariant = colors.outline,
        error = colors.danger,
    )
    CompositionLocalProvider(LocalHalo provides colors) {
        MaterialTheme(colorScheme = scheme, typography = HaloType, content = content)
    }
}
