package com.dev.Tvivo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic app roles can be replaced by a Plus theme without editing controls. */
data class AppColorScheme(
    val bg: Color,
    val surface: Color,
    val elevated: Color,
    val line: Color,
    val ink: Color,
    val dim: Color,
    val accent: Color,
    val accentText: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val focus: Color,
    val focusInner: Color,
    val error: Color,
    val onError: Color,
    val good: Color,
    val favourite: Color,
    val deep: Color,
    val mediaBase: Color,
    val mediaText: Color,
    val mediaMuted: Color,
    val mediaAccent: Color,
    val mediaFocus: Color,
    val mediaScrim: Color,
    val artworkBlue: Color,
    val artworkViolet: Color
)

val DefaultAppColors = AppColorScheme(
    bg = Palette.Bg,
    surface = Palette.Surface,
    elevated = Palette.Elevated,
    line = Palette.Line,
    ink = Palette.Ink,
    dim = Palette.Dim,
    accent = Palette.Accent,
    accentText = Palette.AccentText,
    onAccent = Palette.OnAccent,
    accentSoft = Palette.AccentSoft,
    focus = Palette.AccentSoft,
    focusInner = Color.Transparent,
    error = Palette.AccentText,
    onError = Palette.OnAccent,
    good = Palette.Good,
    favourite = Palette.Favourite,
    deep = Palette.Deep,
    mediaBase = MediaPalette.Base,
    mediaText = MediaPalette.Text,
    mediaMuted = MediaPalette.Muted,
    mediaAccent = MediaPalette.Accent,
    mediaFocus = MediaPalette.Accent,
    mediaScrim = MediaPalette.Scrim,
    artworkBlue = MediaPalette.ArtworkBlue,
    artworkViolet = MediaPalette.ArtworkViolet
)

val LightAppColors = AppColorScheme(
    bg = LightPalette.Bg,
    surface = LightPalette.Surface,
    elevated = LightPalette.Elevated,
    line = LightPalette.Line,
    ink = LightPalette.Ink,
    dim = LightPalette.Dim,
    accent = LightPalette.Accent,
    accentText = LightPalette.AccentText,
    onAccent = LightPalette.OnAccent,
    accentSoft = LightPalette.AccentSoft,
    focus = LightPalette.Focus,
    focusInner = LightPalette.Bg,
    error = LightPalette.AccentText,
    onError = LightPalette.OnAccent,
    good = LightPalette.Good,
    favourite = LightPalette.Favourite,
    deep = LightPalette.Deep,
    mediaBase = MediaPalette.Base,
    mediaText = MediaPalette.Text,
    mediaMuted = MediaPalette.Muted,
    mediaAccent = MediaPalette.Accent,
    mediaFocus = MediaPalette.Accent,
    mediaScrim = MediaPalette.Scrim,
    artworkBlue = MediaPalette.ArtworkBlue,
    artworkViolet = MediaPalette.ArtworkViolet
)

val LocalAppColors = compositionLocalOf { DefaultAppColors }

/** Existing role spelling is retained while consumers move off fixed Palette values. */
object AppColors {
    val Bg: Color @Composable get() = LocalAppColors.current.bg
    val Surface: Color @Composable get() = LocalAppColors.current.surface
    val Elevated: Color @Composable get() = LocalAppColors.current.elevated
    val Line: Color @Composable get() = LocalAppColors.current.line
    val Ink: Color @Composable get() = LocalAppColors.current.ink
    val Dim: Color @Composable get() = LocalAppColors.current.dim
    val Accent: Color @Composable get() = LocalAppColors.current.accent
    val AccentText: Color @Composable get() = LocalAppColors.current.accentText
    val OnAccent: Color @Composable get() = LocalAppColors.current.onAccent
    val AccentSoft: Color @Composable get() = LocalAppColors.current.accentSoft
    val Focus: Color @Composable get() = LocalAppColors.current.focus
    val FocusInner: Color @Composable get() = LocalAppColors.current.focusInner
    val Error: Color @Composable get() = LocalAppColors.current.error
    val OnError: Color @Composable get() = LocalAppColors.current.onError
    val Good: Color @Composable get() = LocalAppColors.current.good
    val Favourite: Color @Composable get() = LocalAppColors.current.favourite
    val Deep: Color @Composable get() = LocalAppColors.current.deep
    val MediaBase: Color @Composable get() = LocalAppColors.current.mediaBase
    val MediaText: Color @Composable get() = LocalAppColors.current.mediaText
    val MediaMuted: Color @Composable get() = LocalAppColors.current.mediaMuted
    val MediaAccent: Color @Composable get() = LocalAppColors.current.mediaAccent
    val MediaFocus: Color @Composable get() = LocalAppColors.current.mediaFocus
    val MediaScrim: Color @Composable get() = LocalAppColors.current.mediaScrim
    val ArtworkBlue: Color @Composable get() = LocalAppColors.current.artworkBlue
    val ArtworkViolet: Color @Composable get() = LocalAppColors.current.artworkViolet
}
