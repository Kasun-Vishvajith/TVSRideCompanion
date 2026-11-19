package dev.tvsopen.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Light canvas and high contrast surfaces for daylight use. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF254CCB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE7FF),
    onPrimaryContainer = Color(0xFF101725),
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE5FE),
    onSecondaryContainer = Color(0xFF101725),
    tertiary = Color(0xFF003ECC),
    tertiaryContainer = Color(0xFFDFE7FF),
    onTertiaryContainer = Color(0xFF101725),
    error = Color(0xFFCF202F),
    onError = Color.White,
    background = Color(0xFFF1F4FA),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101725),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFD8DFEA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
)

@Immutable
data class TilePalette(
    val largeLeft: Color,
    val largeRight: Color,
    val small1: Color,
    val small2: Color,
    val small3: Color,
    val neutral: Color,
    val accent: Color,
    val connected: Color,
)

private val LightTiles = TilePalette(
    largeLeft = Color(0xFFFFFFFF),
    largeRight = Color(0xFFFFFFFF),
    small1 = Color(0xFFFFFFFF),
    small2 = Color(0xFFFFFFFF),
    small3 = Color(0xFFFFFFFF),
    neutral = Color(0xFFFFFFFF),
    accent = Color(0xFFFFFFFF),
    connected = Color(0xFF05B169),   // status dot
)

private val DarkTiles = TilePalette(
    largeLeft = Color(0xFF182233),
    largeRight = Color(0xFF182233),
    small1 = Color(0xFF182233),
    small2 = Color(0xFF182233),
    small3 = Color(0xFF182233),
    neutral = Color(0xFF182233),
    accent = Color(0xFF182233),
    connected = Color(0xFF05B169),   // status dot
)

val LocalTilePalette = staticCompositionLocalOf { LightTiles }

/**
 * The single switch used everywhere.
 *
 * Material defaults derive the off-track from the scheme, which went
 * near-invisible once tiles turned neutral white: a pale track on a
 * near-white tile. This pins both states to explicit tokens — blue track
 * when on, mid-grey track with a white thumb when off — chosen per canvas
 * by surface luminance so a theme-mode override still resolves correctly.
 */
@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Color(0xFF254CCB),
            checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = if (dark) Color(0xFF475569) else Color(0xFFB0BED2),
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
)

/**
 * Tabular numerals, per `DESIGN.md` (`number-display`, weight 500, mono).
 * System monospace: the licensed brand mono has an Inter/JetBrains substitute
 * on the web, but this app ships no fonts and requests no network, so the
 * platform face is the honest stand-in. Applied to telemetry readouts only,
 * never to prose.
 */
val NumberStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
)

/** Dark canvas with raised surfaces and a lighter accent. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7C7FF),
    onPrimary = Color(0xFF0D2A80),
    primaryContainer = Color(0xFF243C74),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFB0BED2),
    secondaryContainer = Color(0xFF263958),
    surfaceContainer = Color(0xFF182233),
    surfaceContainerHighest = Color(0xFF202D42),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFA8B8CC),
    tertiaryContainer = Color(0xFF182233),
    onTertiaryContainer = Color(0xFFFFFFFF),
    error = Color(0xFFCF202F),
    onError = Color.White,
    background = Color(0xFF101725),
    surface = Color(0xFF101725),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF182233),
    onSurfaceVariant = Color(0xFFB0BED2),
    outline = Color(0xFF475569),
)

/**
 * Applies the palette for [mode].
 *
 * [ThemeMode.AUTO] follows the system setting, which is why this takes a
 * `darkTheme` boolean computed by the caller rather than calling
 * `isSystemInDarkTheme()` internally — the caller already needs to read it to
 * keep the two in sync.
 */
@Composable
fun TVSRideTheme(
    mode: ThemeMode = ThemeMode.AUTO,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val useDark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> darkTheme
    }
    CompositionLocalProvider(
        LocalTilePalette provides if (useDark) DarkTiles else LightTiles,
    ) {
        MaterialTheme(
            colorScheme = if (useDark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
            content = content,
        )
    }
}