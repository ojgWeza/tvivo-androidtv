package com.dev.Tvivo.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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

/**
 * `clickable` with the platform's own focus indication switched off — the other half of
 * the one-focus-indicator rule, and the fix for Q-15.
 *
 * `Modifier.clickable` supplies a default indication that paints to the node's
 * **rectangular** bounds. On every control in this app that was invisible, because they
 * were all rectangles and it coincided with their edges. [IconPill] is the first rounded
 * control, and there the rectangle showed up plainly behind the stadium fill — two
 * competing focus indicators on one control, which is exactly the defect Q-3 fixed.
 *
 * Every rectangular call site was therefore a latent instance of the same bug, waiting
 * for someone to give it a corner radius. Use this instead of `clickable` anywhere
 * [tvFocusFrame] is drawing the focus state.
 */
fun Modifier.tvClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        onClick = onClick
    )
}
