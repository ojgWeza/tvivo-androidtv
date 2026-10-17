package com.dev.Tvivo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme as ComposeMaterialTheme
import androidx.compose.material3.darkColorScheme as composeDarkColorScheme
import androidx.compose.material3.lightColorScheme as composeLightColorScheme
import com.dev.Tvivo.ui.ThemeMode
import com.dev.Tvivo.ui.adaptive.AdaptiveEnvironment
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/**
 * [Palette] mapped onto Material colour **roles**, so components that resolve their own
 * colours (buttons, surfaces, the focus indication in `tv-material`) land on the same
 * teal-and-orange system the hand-coloured screens use, instead of Material's default
 * purple.
 *
 * The mapping is deliberate, not mechanical:
 * - `primary` is the accent as a **fill**, and `onPrimary` the ink that sits inside it.
 *   Accent *text on a surface* is a different token — [Palette.AccentText] — because
 *   [Palette.Accent] measures 4.45:1 on `elevated` and misses 4.5. Anything painting
 *   accent text must use `AccentText`; `primary` is for fills. Focus uses `Focus`.
 * - `surface` is [Palette.Elevated] (cards, inputs, menus) rather than
 *   [Palette.Surface], because Material's `surface` is what a raised component paints
 *   itself with. The rail's flatter [Palette.Surface] is `surfaceVariant`.
 * - `border` carries [Palette.Line] so dividers and outlines stop being invented per
 *   screen — Q-1 and Q-3 were both real screens drifting off the colour system.
 * - Errors reuse the accent family. There is exactly one warm hue in this interface
 *   and introducing a red would break that; error *emphasis* is carried by copy and
 *   position, per `docs/ui-scope.md`.
 */
internal fun tvColorSchemeFor(mode: ThemeMode, colors: AppColorScheme) = if (mode == ThemeMode.Dark) darkColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = colors.elevated,
    onPrimaryContainer = colors.ink,
    inversePrimary = colors.onAccent,
    secondary = colors.line,
    onSecondary = colors.ink,
    secondaryContainer = colors.elevated,
    onSecondaryContainer = colors.ink,
    tertiary = colors.accent,
    onTertiary = colors.onAccent,
    tertiaryContainer = colors.elevated,
    onTertiaryContainer = colors.ink,
    background = colors.bg,
    onBackground = colors.ink,
    surface = colors.elevated,
    onSurface = colors.ink,
    surfaceVariant = colors.surface,
    onSurfaceVariant = colors.dim,
    surfaceTint = colors.accent,
    inverseSurface = colors.ink,
    inverseOnSurface = colors.bg,
    border = colors.line,
    borderVariant = colors.line,
    scrim = colors.bg,
    error = colors.error,
    onError = colors.onError,
    errorContainer = colors.elevated,
    onErrorContainer = colors.error
) else lightColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = colors.deep,
    onPrimaryContainer = colors.ink,
    inversePrimary = colors.onAccent,
    secondary = colors.dim,
    onSecondary = colors.bg,
    secondaryContainer = colors.elevated,
    onSecondaryContainer = colors.ink,
    tertiary = colors.good,
    onTertiary = colors.bg,
    tertiaryContainer = colors.elevated,
    onTertiaryContainer = colors.ink,
    background = colors.bg,
    onBackground = colors.ink,
    surface = colors.elevated,
    onSurface = colors.ink,
    surfaceVariant = colors.surface,
    onSurfaceVariant = colors.dim,
    surfaceTint = colors.accent,
    inverseSurface = colors.ink,
    inverseOnSurface = colors.bg,
    border = colors.line,
    borderVariant = colors.line,
    scrim = colors.mediaBase,
    error = colors.error,
    onError = colors.onError,
    errorContainer = colors.elevated,
    onErrorContainer = colors.error
)

/** Compose Material3 owns menus and sliders; give it the same semantic colours as TV M3. */
internal fun composeColorSchemeFor(mode: ThemeMode, colors: AppColorScheme) =
    (if (mode == ThemeMode.Dark) composeDarkColorScheme() else composeLightColorScheme()).copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = if (mode == ThemeMode.Dark) colors.elevated else colors.deep,
        onPrimaryContainer = colors.ink,
        inversePrimary = colors.onAccent,
        secondary = if (mode == ThemeMode.Dark) colors.line else colors.dim,
        onSecondary = if (mode == ThemeMode.Dark) colors.ink else colors.bg,
        secondaryContainer = colors.elevated,
        onSecondaryContainer = colors.ink,
        tertiary = if (mode == ThemeMode.Dark) colors.accent else colors.good,
        onTertiary = if (mode == ThemeMode.Dark) colors.onAccent else colors.bg,
        tertiaryContainer = colors.elevated,
        onTertiaryContainer = colors.ink,
        background = colors.bg,
        onBackground = colors.ink,
        surface = colors.elevated,
        onSurface = colors.ink,
        surfaceVariant = colors.surface,
        onSurfaceVariant = colors.dim,
        surfaceTint = colors.accent,
        inverseSurface = colors.ink,
        inverseOnSurface = colors.bg,
        outline = colors.line,
        outlineVariant = colors.line,
        surfaceDim = colors.bg,
        surfaceBright = if (mode == ThemeMode.Dark) colors.elevated else colors.surface,
        surfaceContainerLowest = colors.bg,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.elevated,
        surfaceContainerHighest = colors.elevated,
        scrim = if (mode == ThemeMode.Dark) colors.bg else colors.mediaBase,
        error = colors.error,
        onError = colors.onError,
        errorContainer = colors.elevated,
        onErrorContainer = colors.error
    )

/** Cards and buttons share a 12 dp corner; pills remain fully rounded. */
object AppShapes {
    val card = RoundedCornerShape(12.dp)
    val button = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(percent = 50)
}

/** The one place the colour roles and the type scale are installed. */
@Composable
fun TvivoTheme(
    mode: ThemeMode = ThemeMode.Dark,
    appColors: AppColorScheme = if (mode == ThemeMode.Dark) DefaultAppColors else LightAppColors,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(colorScheme = tvColorSchemeFor(mode, appColors), typography = TvType.typography) {
            ComposeMaterialTheme(colorScheme = composeColorSchemeFor(mode, appColors)) {
                AdaptiveEnvironment(content = content)
            }
        }
    }
}
