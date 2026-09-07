package com.dev.Tvivo.ui.common

import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.dev.Tvivo.ui.theme.Palette

/**
 * The one focus indicator for the whole app — [ContentGrid]'s static accent frame,
 * lifted out so buttons and Home tiles can't diverge from the grid cards again (Q-3).
 */
fun Modifier.tvFocusFrame(shape: Shape = RectangleShape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = if (focused) Palette.Accent else Color.Transparent,
            shape = shape
        )
}
