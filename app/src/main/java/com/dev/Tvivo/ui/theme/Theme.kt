package com.dev.Tvivo.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

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
 *   accent text must use `AccentText`; `primary` is for fills and frames only.
 * - `surface` is [Palette.Elevated] (cards, inputs, menus) rather than
 *   [Palette.Surface], because Material's `surface` is what a raised component paints
 *   itself with. The rail's flatter [Palette.Surface] is `surfaceVariant`.
 * - `border` carries [Palette.Line] so dividers and outlines stop being invented per
 *   screen — Q-1 and Q-3 were both real screens drifting off the colour system.
 * - Errors reuse the accent family. There is exactly one warm hue in this interface
 *   and introducing a red would break that; error *emphasis* is carried by copy and
 *   position, per `docs/ui-scope.md`.
 */
private val TvivoColorScheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.Elevated,
    onPrimaryContainer = Palette.Ink,
    secondary = Palette.Line,
    onSecondary = Palette.Ink,
    secondaryContainer = Palette.Elevated,
    onSecondaryContainer = Palette.Ink,
    tertiary = Palette.Accent,
    onTertiary = Palette.OnAccent,
    background = Palette.Bg,
    onBackground = Palette.Ink,
    surface = Palette.Elevated,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.Dim,
    border = Palette.Line,
    borderVariant = Palette.Line,
    scrim = Palette.Bg,
    error = Palette.AccentText,
    onError = Palette.OnAccent,
    errorContainer = Palette.Elevated,
    onErrorContainer = Palette.AccentText
)

/** The one place the colour roles and the type scale are installed. */
@Composable
fun TvivoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TvivoColorScheme,
        typography = TvType.typography,
        content = content
    )
}
