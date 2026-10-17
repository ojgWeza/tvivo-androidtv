package com.dev.Tvivo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType

/** Deterministic no-network artwork for missing poster and channel art. */
@Composable
fun ArtworkFallback(title: String, channel: Boolean, modifier: Modifier = Modifier) {
    val accents = listOf(AppColors.MediaAccent, AppColors.Good, AppColors.Favourite,
        AppColors.ArtworkBlue, AppColors.ArtworkViolet)
    val accent = remember(title, accents) {
        accents[Math.floorMod(title.hashCode(), accents.size)]
    }
    val mediaBase = AppColors.MediaBase
    val iconTint = AppColors.MediaText
    Box(
        modifier = modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(mediaBase, mediaBase, accent.copy(alpha = 0.3f).compositeOver(mediaBase)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(if (channel) 28.dp else 36.dp).align(Alignment.Center)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            if (channel) {
                drawRect(iconTint,
                    topLeft = Offset(size.width * .12f, size.height * .22f),
                    size = Size(size.width * .76f, size.height * .55f), style = stroke)
                drawLine(iconTint,
                    Offset(size.width * .36f, size.height * .84f),
                    Offset(size.width * .64f, size.height * .84f), strokeWidth = stroke.width,
                    cap = StrokeCap.Round)
            } else {
                drawRect(iconTint,
                    topLeft = Offset(size.width * .12f, size.height * .12f),
                    size = Size(size.width * .76f, size.height * .76f), style = stroke)
                listOf(.32f, .68f).forEach { y ->
                    drawLine(iconTint,
                        Offset(size.width * .12f, size.height * y),
                        Offset(size.width * .88f, size.height * y), strokeWidth = stroke.width)
                }
            }
        }
        Text(title.ifBlank { "Untitled" }, color = AppColors.MediaText, style = TvType.label,
            maxLines = 2, minLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, mediaBase.copy(alpha = 0.98f))))
                .padding(horizontal = 8.dp, vertical = 6.dp))
    }
}
