package com.dev.Tvivo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The colour system from `docs/ui-scope.md`. Every pair was measured against WCAG;
 * changing one of these means re-measuring, not eyeballing.
 *
 * Two accent tokens on purpose: [Accent] as *text* on [Elevated] measures 4.45:1 and
 * misses the 4.5 threshold, so accent text uses [AccentText] (5.07:1). [Accent] stays
 * for fills and frames, where the bar is 3:1.
 */
object Palette {
    val Bg = Color(0xFF0A1619)
    val Surface = Color(0xFF0F2126)
    val Elevated = Color(0xFF163036)
    val Line = Color(0xFF1E4149)
    val Ink = Color(0xFFE8F1F2)
    val Dim = Color(0xFF93ACB1)
    val Accent = Color(0xFFD97757)
    val AccentText = Color(0xFFE08466)
    val OnAccent = Color(0xFF1A0A05)
}
