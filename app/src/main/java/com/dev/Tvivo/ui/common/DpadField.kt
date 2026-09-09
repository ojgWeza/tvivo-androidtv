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
 * **Q-24 — left/right have to escape too.** The first cut intercepted only up/down and
 * left the horizontal axis to the caret, on the grounds that a user correcting a typo
 * mid-string needs it. That reasoning does not survive contact with the device. The TV
 * IME ships its own ◀ ▶ caret keys, so caret movement is already served; and while the
 * IME is up it owns every arrow press and this modifier is never called at all (Q-10).
 * The only time these events reach the app is once the keyboard is **closed** — which is
 * precisely the moment the user has finished typing and wants to leave. So a field that
 * does not release left/right is a horizontal dead end, which is what the rail's filter
 * became: RIGHT crossed to the grid from a category row and did nothing from the field
 * one row above it.
 *
 * A move is consumed **only if it actually lands somewhere**. Where there is nothing in
 * that direction — LEFT out of the leftmost pane — the event falls through to the field
 * and still moves the caret, so nothing is taken away that had somewhere to go.
 *
 * Where the 2D focus search would pick the wrong neighbour, pair this with an explicit
 * `focusProperties { right = … }` on the field: [moveFocus] honours the override, and on
 * this project the search has had to be overridden rather than trusted every time it has
 * come up (Q-17, and `RailRow`).
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
            // Consumed only on a successful move, so the caret keeps the axis wherever
            // focus has nowhere to go.
            Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
            Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
            else -> false
        }
    }
