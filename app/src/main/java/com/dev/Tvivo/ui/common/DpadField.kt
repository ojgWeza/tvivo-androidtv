package com.dev.Tvivo.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * **Any focusable text field on a TV must carry this, or it is a focus trap.**
 *
 * A Compose text field consumes D-pad up/down to move its own cursor. On a phone that is
 * right; on a remote it is fatal, because the D-pad is the only way out of the field.
 * Intercepting in the *preview* pass moves focus before the field ever sees the event.
 *
 * This started life private to `LoginScreen`, where it was the fix for the very first
 * defect this project found — a login screen that could be typed into but never left.
 * The category and item filters (D-13/D-14) then reproduced the identical trap, because
 * the fix was a local detail of one screen rather than a rule. It lives here now so that
 * adding a field means adding this modifier, and forgetting is visible at the call site.
 *
 * Left/right are deliberately **not** intercepted: inside a field they move the caret,
 * which is what the user wants when correcting a typo mid-string. Vertical is the axis
 * with no in-field meaning worth keeping, so it is the axis that escapes.
 *
 * Note this only helps once the IME is **closed**. While the TV keyboard is up it owns
 * every arrow press and the app sees none of them — that is Q-10, a platform constraint,
 * and it is not what this fixes.
 */
fun Modifier.dpadFieldNavigation(focusManager: FocusManager): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> {
                focusManager.moveFocus(FocusDirection.Down)
                true
            }
            Key.DirectionUp -> {
                focusManager.moveFocus(FocusDirection.Up)
                true
            }
            else -> false
        }
    }
