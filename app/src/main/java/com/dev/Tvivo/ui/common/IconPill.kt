package com.dev.Tvivo.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/**
 * The global-action control: a glyph at rest, glyph + label on focus.
 *
 * Refresh, Account and Exit are global actions, and this is the one component that draws
 * all of them — on Home (D-7) and in the browse header (D-8). Refresh was previously a
 * bare text link in the header and an action row on the Account screen, which made one
 * action look like two different things.
 *
 * **Why it expands rather than always showing its label.** Nothing may stay a mystery
 * glyph at ten feet, but a permanent row of three labels is a permanent row of chrome
 * above the content. Revealing the label on focus costs nothing at rest and answers the
 * question at exactly the moment the user is asking it.
 *
 * [glyph] is a Unicode placeholder until the real icon set lands (T-D1). The mark's
 * visual language — one accent stroke, rounded caps — is what that set gets drawn
 * against; these are deliberately not final.
 */
@Composable
fun IconPill(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .height(PILL_HEIGHT)
            // Collapsed to the glyph, expanded to fit its label. `widthIn` rather than a
            // fixed width: label lengths differ, and a pill sized for the longest one
            // would leave the others padded with dead space.
            .widthIn(min = PILL_HEIGHT)
            .animateContentSize()
            .background(
                color = if (focused) Palette.Accent else Palette.Elevated,
                shape = PILL_SHAPE
            )
            .onFocusChanged { focused = it.isFocused }
            .tvFocusFrame(shape = PILL_SHAPE)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val ink = when {
            focused -> Palette.OnAccent
            enabled -> Palette.Ink
            else -> Palette.Dim
        }
        Text(text = glyph, color = ink, style = TvType.title)
        if (focused) {
            Text(
                text = label,
                color = ink,
                style = TvType.label,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}

/** 88 dp square at rest, so the three pills read as one row of uniform controls. */
private val PILL_HEIGHT = 88.dp

private val PILL_SHAPE = RoundedCornerShape(percent = 50)
