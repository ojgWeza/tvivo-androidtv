package com.dev.Tvivo.ui.common

import androidx.compose.material3.Button as TouchButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.Button as TvButton
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision

/** TV buttons need D-pad focus; phone actions must respond to the first touch. */
@Composable
fun AdaptiveActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (LocalIsTelevision.current) {
        TvButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
    } else {
        TouchButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
    }
}
