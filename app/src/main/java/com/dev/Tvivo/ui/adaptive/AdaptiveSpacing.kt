package com.dev.Tvivo.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class AdaptiveSpacing(
    val outerGutter: Dp,
    val rowGap: Dp,
    val cardGap: Dp
) {
    companion object {
        val x1 = 4.dp
        val x2 = 8.dp
        val x3 = 12.dp
        val x4 = 16.dp
        val x6 = 24.dp
        val x8 = 32.dp
        val x12 = 48.dp

        val Television = AdaptiveSpacing(48.dp, 24.dp, 20.dp)
        val Tablet = AdaptiveSpacing(32.dp, 24.dp, 16.dp)
        val Phone = AdaptiveSpacing(16.dp, 16.dp, 12.dp)
    }
}

@Composable
fun adaptiveSpacing(): AdaptiveSpacing = if (LocalIsTelevision.current) {
    AdaptiveSpacing.Television
} else when (LocalWindowSizeClass.current) {
    WindowSizeClass.Compact -> AdaptiveSpacing.Phone
    WindowSizeClass.Medium, WindowSizeClass.Expanded -> AdaptiveSpacing.Tablet
}
