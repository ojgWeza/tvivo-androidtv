package com.dev.Tvivo.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.televisionDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineBreak
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dev.Tvivo.data.repository.HomeCard
import com.dev.Tvivo.ui.common.TvivoImageLoader
import com.dev.Tvivo.ui.common.ArtworkFallback
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfCard(
    card: HomeCard,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onContextMenu: () -> Unit
) {
    val channel = card.type == ContentType.LIVE
    val television = LocalIsTelevision.current
    val density = if (television) televisionDensity() else null
    val context = LocalContext.current
    val largeText = LocalConfiguration.current.fontScale >= 1.6f
    val width = if (television) density!!.posterWidth.dp else 110.dp
    val height = if (television) { if (channel) density!!.channelHeight.dp else density!!.posterHeight.dp }
        else if (channel) 62.dp else 165.dp
    Column(
        modifier = modifier
            .width(width)
            .testTag("shelf-card-${card.key}")
            .tvFocusFrame(RoundedCornerShape(8.dp))
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU &&
                    it.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    onContextMenu()
                    true
                } else false
            }
            .combinedClickable(onClick = onClick, onLongClick = onContextMenu)
    ) {
        Box(
            Modifier.fillMaxWidth().height(height).background(AppColors.Elevated),
            contentAlignment = Alignment.Center
        ) {
            if (!card.item.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(card.item.imageUrl)
                        .size(220, if (channel) 124 else 330).build(),
                    imageLoader = TvivoImageLoader.get(context),
                    contentDescription = null,
                    contentScale = if (channel) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(height)
                )
            } else {
                ArtworkFallback(card.item.title, channel, Modifier.fillMaxSize(), showTitle = false)
            }
            if (card.isNew) {
                Badge("NEW", Modifier.align(Alignment.TopStart))
            }
            card.item.quality?.let { quality ->
                Badge(quality, Modifier.align(if (channel) Alignment.BottomStart else Alignment.TopStart)
                    .padding(start = if (channel) 6.dp else 0.dp, bottom = if (channel) 8.dp else 0.dp))
            }
            card.progress?.let { fraction ->
                Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomStart)
                    .background(AppColors.Line)) {
                    Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(AppColors.Accent))
                }
            }
        }
        Text(card.item.title, color = AppColors.Ink,
            style = TvType.label.copy(lineBreak = LineBreak.Heading),
            maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis,
            modifier = if (television) Modifier.height(if (largeText) 72.dp else density!!.titleHeight.dp).padding(top = 4.dp)
                else Modifier.padding(top = 5.dp))
        card.typeBadge?.let { badge ->
            val localizedBadge = when (badge) {
                "Movie" -> androidx.compose.ui.res.stringResource(com.dev.Tvivo.R.string.content_type_movie)
                else -> badge
            }
            Text(localizedBadge, color = AppColors.AccentText,
                style = if (television) TvType.label else TvType.caption,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (television) Modifier.height(20.dp) else Modifier)
        }
    }
}

@Composable
private fun Badge(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        color = AppColors.Ink,
        style = TvType.caption,
        maxLines = 1,
        modifier = modifier.background(AppColors.Bg.copy(alpha = 0.88f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}
