package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Draws the category drawer over its caller without changing the caller's measured bounds. */
@Composable
internal fun CategoryPickerOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    drawer: @Composable (Modifier) -> Unit
) {
    if (!visible) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .clickable(onClick = onDismiss)
                .testTag("category-picker-scrim")
        )
        val drawerWidth = (maxWidth * 0.34f).coerceAtLeast(280.dp)
        drawer(
            Modifier.align(Alignment.CenterStart)
                .width(drawerWidth)
                .fillMaxHeight()
                .testTag("category-picker-drawer")
        )
    }
}
