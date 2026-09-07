package com.dev.Tvivo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.Palette

enum class ContentType(val label: String) {
    LIVE("Live TV"),
    MOVIES("Movies"),
    SERIES("Series")
}

/**
 * Three destinations, nothing else. Search is scoped by content type, so the choice
 * made here is what makes the search boundary visible in navigation rather than in a
 * label the user has to read.
 */
@Composable
fun HomeScreen(lastSelected: ContentType?, onSelect: (ContentType) -> Unit) {
    // Focus lands on the tile the user last opened, so Back out of Browse returns them
    // where they were rather than resetting to the left edge every time.
    val restore = remember { FocusRequester() }
    val focusTarget = lastSelected ?: ContentType.LIVE
    LaunchedEffect(Unit) { restore.requestFocus() }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 64.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Tvivo", color = Palette.Ink, fontSize = 40.sp)
        Spacer(Modifier.height(48.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            ContentType.entries.forEach { type ->
                HomeTile(
                    label = type.label,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (type == focusTarget) Modifier.focusRequester(restore) else Modifier),
                    onClick = { onSelect(type) }
                )
            }
        }
    }
}

@Composable
private fun HomeTile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Palette.Elevated),
        // tv-material3 draws its own grey focus outline. Left on, the tile shows two
        // competing rings; the app has exactly one focus indicator (Q-3).
        border = CardDefaults.border(focusedBorder = Border.None),
        modifier = modifier.height(180.dp).tvFocusFrame()
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(text = label, color = Palette.Ink, fontSize = 28.sp)
        }
    }
}
