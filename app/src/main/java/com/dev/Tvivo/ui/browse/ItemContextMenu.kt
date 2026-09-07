package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import com.dev.Tvivo.ui.theme.Palette

/**
 * Long-press OK. There is no movie detail screen — OK plays immediately, and everything
 * a detail screen would have offered lives here instead. Lands in Phase 2 rather than
 * hardening because live and series both copy it.
 */
@Composable
fun ItemContextMenu(
    item: VodStreamEntity,
    onDismiss: () -> Unit,
    onPlay: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(Palette.Elevated)
                .padding(24.dp)
        ) {
            Text(
                text = item.nameDisplay,
                color = Palette.Ink,
                fontSize = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            MenuAction(label = "Play", onClick = onPlay)
            MenuAction(label = "Add to favourites", onClick = onDismiss)
            MenuAction(label = "Close", onClick = onDismiss)
        }
    }
}

@Composable
private fun MenuAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Palette.Ink,
        fontSize = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp)
    )
}
