package com.dev.Tvivo.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import com.dev.Tvivo.ui.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Each row names the actual foreground/background usage and its WCAG minimum. */
class PaletteContrastTest {
    @Test fun newRolesMeetTheirTextAndFocusPairs() {
        val pairs = listOf(
            Triple("AccentSoft on Deep", Palette.AccentSoft to Palette.Deep, 4.5),
            Triple("AccentSoft on Surface", Palette.AccentSoft to Palette.Surface, 4.5),
            Triple("AccentSoft on Elevated", Palette.AccentSoft to Palette.Elevated, 4.5),
            Triple("AccentSoft on Bg", Palette.AccentSoft to Palette.Bg, 4.5),
            Triple("Good on Surface", Palette.Good to Palette.Surface, 4.5),
            Triple("Good on Elevated", Palette.Good to Palette.Elevated, 4.5),
            Triple("Favourite on Surface", Palette.Favourite to Palette.Surface, 4.5),
            Triple("Favourite on Elevated", Palette.Favourite to Palette.Elevated, 4.5),
            Triple("Ink on Deep", Palette.Ink to Palette.Deep, 4.5),
            Triple("Ink on Bg", Palette.Ink to Palette.Bg, 4.5),
            Triple("Dim on Bg (hints and See all)", Palette.Dim to Palette.Bg, 4.5),
            Triple("Dim on Surface (drawer counts and hints)", Palette.Dim to Palette.Surface, 4.5),
            Triple("Dim on Elevated", Palette.Dim to Palette.Elevated, 4.5),
            Triple("AccentText on Bg (selected tab)", Palette.AccentText to Palette.Bg, 4.5),
            Triple("Dim on Deep", Palette.Dim to Palette.Deep, 4.5),
            Triple("OnAccent on selected Accent fill", Palette.OnAccent to Palette.Accent, 4.5),
            Triple("Focus border on Deep", Palette.AccentSoft to Palette.Deep, 3.0),
            Triple("Focus border on Surface", Palette.AccentSoft to Palette.Surface, 3.0),
            Triple("Focus border on Elevated", Palette.AccentSoft to Palette.Elevated, 3.0),
            Triple("Focus border on Bg", Palette.AccentSoft to Palette.Bg, 3.0)
        )
        pairs.forEach { (name, colors, minimum) ->
            val measured = contrast(colors.first, colors.second)
            assertTrue("$name: $measured < $minimum", measured >= minimum)
        }
    }

    @Test fun lightRolesMeetTextAndControlThresholds() {
        val colors = LightAppColors
        val surfaces = listOf(colors.bg, colors.surface, colors.elevated, colors.deep)
        val textRoles = mapOf(
            "ink" to colors.ink,
            "dim" to colors.dim,
            "accent text" to colors.accentText,
            "good" to colors.good,
            "favourite" to colors.favourite
        )
        surfaces.forEachIndexed { index, surface ->
            textRoles.forEach { (role, foreground) ->
                assertContrast("light $role on surface $index", foreground, surface, 4.5)
            }
            assertContrast("light focus on surface $index", colors.focus, surface, 3.0)
            assertContrast("light control fill on surface $index", colors.accent, surface, 3.0)
            assertContrast("light outline on surface $index", colors.line, surface, 3.0)
        }
        assertContrast("light button text", colors.onAccent, colors.accent, 4.5)
        assertContrast("light inner focus on dark artwork", colors.focusInner, colors.mediaBase, 3.0)
        assertContrast("light error text", colors.error, colors.elevated, 4.5)
        assertContrast("light error button", colors.onError, colors.error, 4.5)
        assertContrast("light secondary button", colors.bg, colors.dim, 4.5)
        assertContrast("light positive button", colors.bg, colors.good, 4.5)
    }

    @Test fun mediaRolesHoldAgainstBrightAndDarkMedia() {
        listOf(DefaultAppColors, LightAppColors).forEach { colors ->
            val overWhite = colors.mediaScrim.compositeOver(Color.White)
            val overBlack = colors.mediaScrim.compositeOver(Color.Black)
            listOf(overWhite, overBlack).forEachIndexed { index, scrim ->
                assertContrast("media text on scrim $index", colors.mediaText, scrim, 4.5)
                assertContrast("media muted on scrim $index", colors.mediaMuted, scrim, 4.5)
                assertContrast("media accent on scrim $index", colors.mediaAccent, scrim, 3.0)
            }
            assertContrast("media focus on control", colors.mediaFocus, colors.mediaBase, 3.0)
        }
    }

    @Test fun lightMaterialSchemesUseSemanticRoles() {
        val colors = LightAppColors
        val tv = tvColorSchemeFor(ThemeMode.Light, colors)
        val compose = composeColorSchemeFor(ThemeMode.Light, colors)
        assertEquals(colors.bg, tv.background)
        assertEquals(colors.elevated, tv.surface)
        assertEquals(colors.accent, tv.primary)
        assertEquals(colors.onAccent, tv.onPrimary)
        assertEquals(colors.line, tv.border)
        assertEquals(colors.error, tv.error)
        assertEquals(colors.bg, compose.background)
        assertEquals(colors.elevated, compose.surface)
        assertEquals(colors.accent, compose.primary)
        assertEquals(colors.onAccent, compose.onPrimary)
        assertEquals(colors.line, compose.outline)
        assertEquals(colors.surface, compose.surfaceContainer)
        assertEquals(colors.elevated, compose.surfaceContainerHigh)
        assertEquals(colors.error, compose.error)
    }

    private fun assertContrast(name: String, foreground: Color, background: Color, minimum: Double) {
        val measured = contrast(foreground, background)
        assertTrue("$name: $measured < $minimum", measured >= minimum)
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xff) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
