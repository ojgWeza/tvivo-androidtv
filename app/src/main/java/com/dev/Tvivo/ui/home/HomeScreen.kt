package com.dev.Tvivo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
 * Three destinations, plus the account. Search is scoped by content type, so the choice
 * made here is what makes the search boundary visible in navigation rather than in a
 * label the user has to read.
 *
 * [accountSummary] is the account and its expiry on one line. It sits on the first
 * screen after login on purpose: a lapsed subscription otherwise presents as "every
 * stream is broken", which is a much longer thing to work out.
 */
@Composable
fun HomeScreen(
    lastSelected: ContentType?,
    accountSummary: String?,
    accountWarning: Boolean,
    onSelect: (ContentType) -> Unit,
    onOpenSettings: () -> Unit
) {
    // Focus lands on the tile the user last opened, so Back out of Browse returns them
    // where they were rather than resetting to the left edge every time.
    val restore = remember { FocusRequester() }
    val focusTarget = lastSelected ?: ContentType.LIVE
    LaunchedEffect(Unit) { restore.requestFocus() }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 64.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Tvivo", color = Palette.Ink, fontSize = 40.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                accountSummary?.let {
                    Text(
                        text = it,
                        color = if (accountWarning) Palette.AccentText else Palette.Dim,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(end = 20.dp)
                    )
                }
                // Sign out, switch account, refresh everything and exit all live behind
                // this. Reached by pressing UP from the tiles.
                Text(
                    text = "Account",
                    color = Palette.AccentText,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .tvFocusFrame()
                        .clickable { onOpenSettings() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

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
