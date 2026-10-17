package com.dev.Tvivo.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.dev.Tvivo.ui.theme.TvType

/** Six stable roles; the TV branch preserves the current 32/24/18/16/14/12 scale. */
data class AdaptiveType(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle
) {
    companion object {
        private fun scale(
            display: Int, headline: Int, title: Int, body: Int, label: Int
        ) = AdaptiveType(
            TvType.display.copy(fontSize = display.sp, lineHeight = (display + 8).sp),
            TvType.headline.copy(fontSize = headline.sp, lineHeight = (headline + 8).sp),
            TvType.title.copy(fontSize = title.sp, lineHeight = (title + 6).sp),
            TvType.body.copy(fontSize = body.sp, lineHeight = (body + 6).sp),
            TvType.label.copy(fontSize = label.sp, lineHeight = (label + 6).sp),
            TvType.caption
        )

        val Television = AdaptiveType(
            TvType.display, TvType.headline, TvType.title,
            TvType.body, TvType.label, TvType.caption
        )
        val Tablet = scale(28, 24, 18, 16, 14)
        val Phone = scale(26, 22, 18, 14, 14)
    }
}

@Composable
fun adaptiveType(): AdaptiveType = if (LocalIsTelevision.current) {
    AdaptiveType.Television
} else when (LocalWindowSizeClass.current) {
    WindowSizeClass.Compact -> AdaptiveType.Phone
    WindowSizeClass.Medium, WindowSizeClass.Expanded -> AdaptiveType.Tablet
}
