package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/**
 * Long-press OK. There is no detail screen — OK plays immediately, and everything a
 * detail screen would have offered lives here instead. Typed to [BrowseItem], so live
 * and series reuse it rather than copying it.
 */
@Composable
fun ItemContextMenu(
    item: BrowseItem,
    isFavourite: Boolean,
    hasResumePoint: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
    onClearResume: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(Palette.Elevated)
                .padding(24.dp)
        ) {
            Text(
                text = item.title,
                color = Palette.Ink,
                style = TvType.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            MenuAction(label = "Play", onClick = onPlay)
            MenuAction(
                label = if (isFavourite) "Remove from favourites" else "Add to favourites",
                onClick = onToggleFavourite
            )
            if (hasResumePoint) {
                MenuAction(label = "Remove from continue watching", onClick = onClearResume)
            }
            MenuAction(label = "Close", onClick = onDismiss)
        }
    }
}

@Composable
private fun MenuAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Palette.Ink,
        style = TvType.title,
        modifier = Modifier
            .fillMaxWidth()
            .tvClickable { onClick() }
            .padding(vertical = 14.dp)
    )
}
