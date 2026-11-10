package com.zero7goat.taz.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.zero7goat.taz.core.settings.ThemeMode

private val LightColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = White,
    primaryContainer = Gray150,
    onPrimaryContainer = Ink,
    inversePrimary = Gray100,
    secondary = Gray600,
    onSecondary = White,
    secondaryContainer = Gray200,
    onSecondaryContainer = Ink,
    tertiary = Gray500,
    onTertiary = White,
    tertiaryContainer = Gray100,
    onTertiaryContainer = Ink,
    background = White,
    onBackground = Ink,
    surface = White,
    onSurface = Ink,
    surfaceVariant = Gray150,
    onSurfaceVariant = Gray500,
    surfaceTint = Ink,
    inverseSurface = Gray800,
    inverseOnSurface = Gray100,
    error = ErrorLight,
    onError = White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    outline = Gray400,
    outlineVariant = Gray250,
    scrim = Black,
    surfaceBright = White,
    surfaceDim = Gray200,
    surfaceContainerLowest = White,
    surfaceContainerLow = Gray50,
    surfaceContainer = Gray100,
    surfaceContainerHigh = Gray150,
    surfaceContainerHighest = Gray200
)

private val DarkColorScheme = darkColorScheme(
    primary = Gray100,
    onPrimary = Gray900,
    primaryContainer = Gray700,
    onPrimaryContainer = Gray100,
    inversePrimary = Ink,
    secondary = Gray300,
    onSecondary = Gray900,
    secondaryContainer = Gray700,
    onSecondaryContainer = Gray100,
    tertiary = Gray350,
    onTertiary = Gray900,
    tertiaryContainer = Gray750,
    onTertiaryContainer = Gray100,
    background = Gray900,
    onBackground = Gray100,
    surface = Gray900,
    onSurface = Gray100,
    surfaceVariant = Gray700,
    onSurfaceVariant = Gray350,
    surfaceTint = Gray100,
    inverseSurface = Gray100,
    inverseOnSurface = Gray800,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = Gray450,
    outlineVariant = Gray650,
    scrim = Black,
    surfaceBright = Gray700,
    surfaceDim = Gray900,
    surfaceContainerLowest = Gray950,
    surfaceContainerLow = Gray850,
    surfaceContainer = Gray800,
    surfaceContainerHigh = Gray750,
    surfaceContainerHighest = Gray700
)

// AMOLED = the dark scheme with true black backgrounds and slightly deeper surfaces.
private val AmoledColorScheme = DarkColorScheme.copy(
    primaryContainer = AmoledSurfaceHighest,
    secondaryContainer = AmoledSurfaceHighest,
    tertiaryContainer = AmoledSurfaceHigh,
    background = Black,
    surface = Black,
    surfaceVariant = AmoledSurfaceHigh,
    outlineVariant = AmoledOutlineVariant,
    surfaceBright = AmoledSurfaceBright,
    surfaceDim = Black,
    surfaceContainerLowest = Black,
    surfaceContainerLow = AmoledSurfaceLow,
    surfaceContainer = AmoledSurfaceContainer,
    surfaceContainerHigh = AmoledSurfaceHigh,
    surfaceContainerHighest = AmoledSurfaceHighest
)

/**
 * Whether this theme mode is drawn dark. [systemDark] is the device setting and only matters for
 * [ThemeMode.SYSTEM]. AMOLED is a dark theme with true black backgrounds.
 */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.AMOLED -> true
}

/** True while the theme in effect is dark: Dark, AMOLED, or System on a device that is currently dark. */
val LocalDarkTheme = staticCompositionLocalOf { false }

private const val ThemeFadeMillis = 220

@Composable
private fun animatedColor(target: Color): Color =
    animateColorAsState(targetValue = target, animationSpec = tween(ThemeFadeMillis), label = "theme").value

/**
 * The scheme with every role eased toward its new value, so changing the theme fades instead of
 * flashing. On first composition there is nothing to animate from, so the app opens in the right
 * theme immediately. Animator-duration settings are respected by the animation system.
 */
@Composable
private fun ColorScheme.animated(): ColorScheme = copy(
    primary = animatedColor(primary),
    onPrimary = animatedColor(onPrimary),
    primaryContainer = animatedColor(primaryContainer),
    onPrimaryContainer = animatedColor(onPrimaryContainer),
    inversePrimary = animatedColor(inversePrimary),
    secondary = animatedColor(secondary),
    onSecondary = animatedColor(onSecondary),
    secondaryContainer = animatedColor(secondaryContainer),
    onSecondaryContainer = animatedColor(onSecondaryContainer),
    tertiary = animatedColor(tertiary),
    onTertiary = animatedColor(onTertiary),
    tertiaryContainer = animatedColor(tertiaryContainer),
    onTertiaryContainer = animatedColor(onTertiaryContainer),
    background = animatedColor(background),
    onBackground = animatedColor(onBackground),
    surface = animatedColor(surface),
    onSurface = animatedColor(onSurface),
    surfaceVariant = animatedColor(surfaceVariant),
    onSurfaceVariant = animatedColor(onSurfaceVariant),
    surfaceTint = animatedColor(surfaceTint),
    inverseSurface = animatedColor(inverseSurface),
    inverseOnSurface = animatedColor(inverseOnSurface),
    error = animatedColor(error),
    onError = animatedColor(onError),
    errorContainer = animatedColor(errorContainer),
    onErrorContainer = animatedColor(onErrorContainer),
    outline = animatedColor(outline),
    outlineVariant = animatedColor(outlineVariant),
    scrim = animatedColor(scrim),
    surfaceBright = animatedColor(surfaceBright),
    surfaceDim = animatedColor(surfaceDim),
    surfaceContainer = animatedColor(surfaceContainer),
    surfaceContainerHigh = animatedColor(surfaceContainerHigh),
    surfaceContainerHighest = animatedColor(surfaceContainerHighest),
    surfaceContainerLow = animatedColor(surfaceContainerLow),
    surfaceContainerLowest = animatedColor(surfaceContainerLowest)
)

/**
 * TAZ theme. Uses the app's own palettes only; Material dynamic color is intentionally not used.
 *
 * @param themeMode the saved appearance setting. Defaults to following the system.
 * @param fontFamily the saved font, applied across the whole type scale. Defaults to the system font.
 */
@Composable
fun TAZTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    fontFamily: FontFamily = FontFamily.Default,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = themeMode.isDark(systemDark)
    val target = when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkColorScheme else LightColorScheme
        ThemeMode.LIGHT -> LightColorScheme
        ThemeMode.DARK -> DarkColorScheme
        ThemeMode.AMOLED -> AmoledColorScheme
    }
    val colorScheme = target.animated()
    val typography = remember(fontFamily) { tazTypography(fontFamily) }

    CompositionLocalProvider(LocalDarkTheme provides isDark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
