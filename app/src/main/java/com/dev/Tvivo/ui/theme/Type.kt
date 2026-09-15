package com.dev.Tvivo.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

/**
 * The type scale from `docs/ui-scope.md`, as **roles**. No composable picks an `sp`
 * value: text maps to a role here, and the role owns size, line height and metrics.
 *
 * **12 sp is the floor.** Below it nothing is legible at ten feet, which is the only
 * reason any of these numbers are what they are. The quality badge used to be 10 sp.
 *
 * **Every role sets `lineHeight` explicitly, and that is not cosmetic.** Roboto has no
 * Arabic, so Android falls back to Noto Naskh, whose metrics differ. An English and an
 * Arabic title in the same grid row do not share a baseline unless the line box is
 * pinned — hence `includeFontPadding = false` plus `LineHeightStyle(trim = None,
 * alignment = Center)` on all of them.
 */
object TvType {
    private val metrics = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )

    @Suppress("DEPRECATION")
    private fun role(size: Int, lineHeight: Int) = TextStyle(
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = metrics
    )

    /** Screen wordmark, `Account`, `Subscription`. */
    val display = role(32, 40)

    /** Screen title, category name in the grid header. */
    val headline = role(24, 32)

    /** Rail labels, action rows, dialog title. */
    val title = role(18, 24)

    /** Field values, hints, metadata. */
    val body = role(16, 22)

    /** Card titles, buttons, icon-pill labels. */
    val label = role(14, 20)

    /** Quality badge, field labels. **Floor — never go under.** */
    val caption = role(12, 16)

    /**
     * The same six roles as a Material [Typography], so a composable that takes the
     * theme default rather than naming a role still lands inside the scale. The slots
     * are mapped by size, not by their Material names.
     */
    val typography = Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = headline,
        headlineMedium = headline,
        headlineSmall = headline,
        titleLarge = title,
        titleMedium = title,
        titleSmall = title,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = label,
        labelLarge = label,
        labelMedium = label,
        labelSmall = caption
    )
}
