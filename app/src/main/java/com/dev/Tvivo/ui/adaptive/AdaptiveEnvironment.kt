package com.dev.Tvivo.ui.adaptive

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

enum class WindowSizeClass { Compact, Medium, Expanded }

val LocalIsTelevision = compositionLocalOf { false }
val LocalWindowSizeClass = compositionLocalOf { WindowSizeClass.Compact }

/** Classification follows the current window, and TV input wins over width. */
@Composable
fun AdaptiveEnvironment(
    televisionOverride: Boolean? = null,
    widthOverrideDp: Int? = null,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val packageManager = LocalContext.current.packageManager
    val isTelevision = televisionOverride ?: run {
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION ||
            (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
    }
    val width = widthOverrideDp ?: configuration.screenWidthDp
    val sizeClass = when {
        width < 600 -> WindowSizeClass.Compact
        width < 840 -> WindowSizeClass.Medium
        else -> WindowSizeClass.Expanded
    }
    CompositionLocalProvider(
        LocalIsTelevision provides isTelevision,
        LocalWindowSizeClass provides sizeClass,
        content = content
    )
}
