package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/**
 * Offered when a resume position exists. Starting over silently would lose the user's
 * place; resuming silently would surprise anyone who wanted the beginning.
 */
@Composable
fun ResumePrompt(
    title: String,
    positionMs: Long,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(Palette.Elevated)
                .padding(24.dp)
        ) {
            Text(
                text = title,
                color = Palette.Ink,
                style = TvType.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            PromptAction("Resume from ${formatPosition(positionMs)}", onResume)
            PromptAction("Start over", onStartOver)
        }
    }
}

@Composable
private fun PromptAction(label: String, onClick: () -> Unit) {
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

/** `34:12`, or `1:34:12` once past an hour. */
internal fun formatPosition(positionMs: Long): String {
    val totalSeconds = positionMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
