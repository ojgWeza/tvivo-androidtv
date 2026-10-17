package com.dev.Tvivo.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.browse.asRatingLabel
import com.dev.Tvivo.ui.common.ScrollableText
import com.dev.Tvivo.ui.common.ArtworkFallback
import com.dev.Tvivo.ui.common.TvivoImageLoader
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType

/** Shared metadata surface for movie and series. Unknown fields are omitted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailHero(
    title: String,
    imageUrl: String?,
    plot: String?,
    quality: String?,
    year: String?,
    rating: Double?,
    durationMinutes: Int? = null,
    genre: String? = null,
    cast: String? = null,
    isLive: Boolean = false,
    horizontal: Boolean = false,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    var expanded by remember(title) { mutableStateOf(false) }
    val artwork: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().aspectRatio(if (isLive) 16f / 9f else 2f / 3f)
            .clip(RoundedCornerShape(8.dp)).background(AppColors.Elevated), contentAlignment = Alignment.Center) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUrl).size(440, if (isLive) 248 else 660).build(),
                    imageLoader = TvivoImageLoader.get(context),
                    contentDescription = "$title poster",
                    contentScale = if (isLive) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ArtworkFallback(title, isLive)
            }
        }
    }
    val details: @Composable () -> Unit = {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
        Text(title.ifBlank { "Untitled" }, color = AppColors.Ink, style = TvType.headline,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        val facts = listOfNotNull(year?.takeIf { it.isNotBlank() },
            rating?.let { "${it.asRatingLabel()} / 10" }, quality?.takeIf { it.isNotBlank() },
            durationMinutes?.takeIf { it > 0 }?.let { "$it min" })
        if (facts.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                facts.forEach { fact ->
                    Text(fact, color = AppColors.Ink, style = TvType.label,
                        modifier = Modifier.background(AppColors.Elevated, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        }
        listOfNotNull(genre?.takeIf { it.isNotBlank() }, cast?.takeIf { it.isNotBlank() })
            .forEach { value ->
                Text(value, color = AppColors.Dim, style = TvType.label,
                    modifier = Modifier.padding(top = 6.dp))
            }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.widthIn(max = 320.dp).fillMaxWidth()) { actions() }
        plot?.takeIf { it.isNotBlank() }?.let { synopsis ->
            Spacer(Modifier.height(14.dp))
            if (!horizontal && !expanded && synopsis.length > 180) {
                Text(synopsis.take(180).trimEnd() + "…", color = AppColors.Dim,
                    style = TvType.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
                DetailTextButton("Read more", Modifier.padding(top = 4.dp)) { expanded = true }
            } else {
                ScrollableText(synopsis, maxHeight = if (horizontal) 240.dp else 300.dp)
                if (!horizontal && expanded) DetailTextButton("Show less") { expanded = false }
            }
        }
        }
    }
    if (horizontal) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(3f).widthIn(max = 320.dp)) { artwork() }
            Box(Modifier.weight(7f)) { details() }
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.Start) {
            Box(Modifier.widthIn(max = 220.dp).fillMaxWidth()) { artwork() }
            Spacer(Modifier.height(16.dp))
            details()
        }
    }
}

@Composable
fun DetailTextButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(min = 52.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(if (focused) AppColors.Accent else AppColors.Elevated)
        .onFocusChanged { focused = it.isFocused }
        .tvFocusFrame(RoundedCornerShape(8.dp))
        .semantics { contentDescription = label }
        .tvClickable { onClick() }, contentAlignment = Alignment.CenterStart) {
        Text(label, color = if (focused) AppColors.OnAccent else AppColors.Ink,
            style = TvType.label, modifier = Modifier.padding(horizontal = 16.dp))
    }
}
