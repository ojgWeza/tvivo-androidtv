package com.dev.Tvivo.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.launch

/**
 * A block of body text that is **focusable so that it can actually be scrolled**, for
 * synopses too long to fit their pane.
 *
 * **The bug this exists to fix.** The pre-run page already wrapped the plot in
 * `verticalScroll(rememberScrollState())`, with a comment explaining that it was
 * deliberately not focusable so it would never become a D-pad stop between Play and the
 * rail. On a device with no pointer and no wheel, that combination cannot work: a scroll
 * container that nothing can focus has no way to receive a scroll gesture, so the text
 * clipped silently at the bottom of its column — no ellipsis, no affordance, and no way
 * to read the rest. The episode picker took the other road and hard-truncated to two
 * lines with `TextOverflow.Ellipsis`, which at least admitted there was more, but still
 * offered no way to see it.
 *
 * **Why focusable is safe here, given that concern was real.** The worry was a dead stop
 * the user has to press through on the way from Play to the rail. Two things prevent it:
 *
 *  - The block only takes focus **when it actually overflows**. Text that fits is drawn
 *    as a plain `Text` with no focus target, so on the great majority of items — this
 *    panel's synopses are two or three lines — D-pad traversal is exactly as it was.
 *  - When it does take focus, UP and DOWN scroll *only while there is somewhere to
 *    scroll*. At the top, UP leaves upward; at the bottom, DOWN leaves downward. So it
 *    behaves like one long row rather than a trap, and it can never swallow the press
 *    that was meant to get past it. This is the same rule [dpadFieldNavigation] applies
 *    to text fields, for the same reason.
 *
 * A focus frame and a `▾ more` hint mark it as a place the user can go, because a block
 * of text that quietly becomes focusable is not discoverable.
 */
@Composable
fun ScrollableText(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 220.dp,
    color: Color = Palette.Dim,
    style: androidx.compose.ui.text.TextStyle = TvType.body
) {
    val scroll = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }

    // **`verticalScroll` is attached unconditionally, focus is not.**
    //
    // `maxValue` starts at `Int.MAX_VALUE` and is only given a real value by the scroll
    // modifier during layout, so "does this overflow" cannot gate the modifier that
    // answers it — doing so is circular and the block would never become scrollable at
    // all. The scroll is therefore always present (harmless, and exactly what was here
    // before) and only the *focusability* is conditional, which is the part that has a
    // cost in traversal.
    val overflows = scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE
    val direction = paragraphDirection(text)

    // A block that stops overflowing (shorter plot loaded into the same slot) must not
    // keep focus it can no longer justify.
    LaunchedEffect(overflows) {
        if (!overflows && focused) focusManager.moveFocus(FocusDirection.Down)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = color,
            style = style.copy(
                textDirection = direction,
                textAlign = paragraphAlignment(text)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .then(
                    if (overflows) {
                        Modifier
                            .border(
                                width = if (focused) 2.dp else 0.dp,
                                color = if (focused) Palette.Accent else Color.Transparent,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .onFocusChanged { focused = it.isFocused }
                            // Preview pass, before the scroll container sees the event —
                            // same reason `dpadFieldNavigation` uses it.
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) {
                                    return@onPreviewKeyEvent false
                                }
                                when (event.key) {
                                    // Returning false at the boundary is what stops this
                                    // being a trap: the press falls through and Compose
                                    // moves focus out of the block.
                                    Key.DirectionDown ->
                                        if (scroll.value < scroll.maxValue) {
                                            scope.launch { scroll.stepBy(SCROLL_STEP) }
                                            true
                                        } else {
                                            false
                                        }
                                    Key.DirectionUp ->
                                        if (scroll.value > 0) {
                                            scope.launch { scroll.stepBy(-SCROLL_STEP) }
                                            true
                                        } else {
                                            false
                                        }
                                    else -> false
                                }
                            }
                            .focusable()
                    } else {
                        Modifier
                    }
                )
                .verticalScroll(scroll)
                .padding(6.dp)
        )

        // Only while there is more below: a hint that stays put once the user has reached
        // the end is telling them something untrue.
        if (overflows && scroll.value < scroll.maxValue) {
            Text(
                text = "▾ more",
                color = if (focused) Palette.AccentText else Palette.Dim,
                style = TvType.caption,
                modifier = Modifier.padding(top = 4.dp, start = 6.dp)
            )
        }
    }
}

private suspend fun androidx.compose.foundation.ScrollState.stepBy(delta: Float) {
    animateScrollTo((value + delta).toInt().coerceIn(0, maxValue))
}

/** Roughly three lines of `body`, so one press moves a readable amount. */
private const val SCROLL_STEP = 90f
