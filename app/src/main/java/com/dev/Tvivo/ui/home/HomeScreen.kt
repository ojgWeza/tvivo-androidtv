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
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.Palette

enum class ContentType { LIVE, MOVIES, SERIES }

/**
 * Three destinations, nothing else. Search is scoped by content type, so the choice
 * made here is what makes the search boundary visible in navigation rather than in a
 * label the user has to read.
 */
@Composable
fun HomeScreen(onSelect: (ContentType) -> Unit) {
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstCard.requestFocus() }

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
            HomeTile("Live TV", Modifier.weight(1f).focusRequester(firstCard)) { onSelect(ContentType.LIVE) }
            HomeTile("Movies", Modifier.weight(1f)) { onSelect(ContentType.MOVIES) }
            HomeTile("Series", Modifier.weight(1f)) { onSelect(ContentType.SERIES) }
        }
    }
}

@Composable
private fun HomeTile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Palette.Elevated),
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
