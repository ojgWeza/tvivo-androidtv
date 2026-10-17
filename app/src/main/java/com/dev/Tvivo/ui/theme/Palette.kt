package com.dev.Tvivo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The colour system from `docs/ui-scope.md`. Every pair was measured against WCAG;
 * changing one of these means re-measuring, not eyeballing.
 *
 * Two accent tokens on purpose: [Accent] as *text* on [Elevated] measures 4.45:1 and
 * misses the 4.5 threshold, so accent text uses [AccentText] (5.07:1). [Accent] stays
 * for fills, where the bar is 3:1. Focus uses its own high-contrast role.
 */
object Palette {
    val Bg = Color(0xFF0A1619)
    val Surface = Color(0xFF0F2126)
    val Elevated = Color(0xFF163036)
    val Line = Color(0xFF1E4149)
    val Ink = Color(0xFFE8F1F2)
    val Dim = Color(0xFFB0C4C8)
    val Accent = Color(0xFFD97757)
    val AccentText = Color(0xFFE08466)
    val OnAccent = Color(0xFF1A0A05)
    val AccentSoft = Color(0xFFE7A084)
    val Good = Color(0xFF8BC8A0)
    val Favourite = Color(0xFFF2C94C)
    val Deep = Color(0xFF071114)
}

/** Warm light surfaces follow WinUI's Light dictionary; interactive tones are darkened for AA. */
object LightPalette {
    val Bg = Color(0xFFFFF5E5)
    val Surface = Color(0xFFFFF9EF)
    val Elevated = Color(0xFFEBD9BA)
    val Line = Color(0xFF6B6253)
    val Ink = Color(0xFF0F2126)
    val Dim = Color(0xFF1B5557)
    val Accent = Color(0xFF98452F)
    val AccentText = Color(0xFF98452F)
    val OnAccent = Color(0xFFFFF9EF)
    val AccentSoft = Color(0xFF98452F)
    val Focus = Color(0xFF12383F)
    val Good = Color(0xFF14664B)
    val Favourite = Color(0xFF814A00)
    val Deep = Color(0xFFF4E5CC)
}

/** Media and artwork stay dark in either theme so text survives unknown video/art colours. */
object MediaPalette {
    val Base = Color(0xFF071114)
    val Text = Color(0xFFFFF5E5)
    val Muted = Color(0xFFD2E1E3)
    val Accent = Color(0xFFE7A084)
    val Scrim = Color(0xE0071114)
    val ArtworkBlue = Color(0xFF6B8FA3)
    val ArtworkViolet = Color(0xFF80658D)
}
