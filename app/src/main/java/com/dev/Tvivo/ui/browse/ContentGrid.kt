package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dev.Tvivo.ui.common.TvivoImageLoader
import com.dev.Tvivo.ui.theme.Palette

/**
 * The one grid, for every content type. Four things live here rather than in hardening,
 * because everything reuses them: placeholders on with stable keys, focus restoration by
 * item ID, the static focus frame, and the long-press menu.
 *
 * [cardShape] is the only thing live changes — a 16:9 channel tile instead of a 2:3
 * poster. It is a pixel size fed to the image pipeline, not styling; see
 * [TvivoImageLoader].
 */
@Composable
fun ContentGrid(
    items: LazyPagingItems<BrowseItem>,
    pendingFocusItemId: Int?,
    cardShape: CardShape,
    /** The rail's RIGHT target; a focus group forwards it to its first focusable card. */
    gridFocusRequester: FocusRequester,
    onPlay: (BrowseItem) -> Unit,
    onContextMenu: (BrowseItem) -> Unit,
    onFocused: (BrowseItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val restoreRequester = remember { FocusRequester() }
    var restored by remember(pendingFocusItemId) { mutableStateOf(pendingFocusItemId == null) }

    // Restoration is by stable item ID with a nearest-index fallback, never by raw index:
    // the list can have shifted under us while a stream was playing.
    LaunchedEffect(pendingFocusItemId, items.itemCount) {
        if (restored || pendingFocusItemId == null || items.itemCount == 0) return@LaunchedEffect
        val index = (0 until items.itemCount).firstOrNull { i ->
            items.peek(i)?.id == pendingFocusItemId
        }
        if (index != null) {
            gridState.scrollToItem(index)
            restored = true
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = gridState,
        modifier = modifier.fillMaxSize().focusRequester(gridFocusRequester).focusGroup(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        items(
            count = items.itemCount,
            // Without stable keys, recomposition after an append rebinds focus onto the
            // wrong item.
            key = items.itemKey { it.id }
        ) { index ->
            val item = items[index]
            StreamCard(
                item = item,
                cardShape = cardShape,
                modifier = if (item?.id == pendingFocusItemId) {
                    Modifier.focusRequester(restoreRequester)
                } else {
                    Modifier
                },
                onPlay = { item?.let(onPlay) },
                onLongPress = { item?.let(onContextMenu) },
                onFocused = { item?.let(onFocused) }
            )
        }

        if (items.loadState.append is androidx.paging.LoadState.Loading) {
            item {
                // The loading footer must not be focusable, or the user D-pads onto a
                // spinner and cannot get past it.
                Box(
                    modifier = Modifier
                        .height(cardShape.heightDp())
                        .focusProperties { canFocus = false },
                    contentAlignment = Alignment.Center
                ) {
                    Text("…", color = Palette.Dim)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamCard(
    item: BrowseItem?,
    cardShape: CardShape,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onFocused: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(width = cardShape.widthDp(), height = cardShape.heightDp())
                .background(Palette.Elevated)
                // Static frame, no scale: a scaling card at 3 m reads as wobble, and
                // reflowing neighbours makes the grid feel unstable under fast scroll.
                .border(
                    width = if (focused) 3.dp else 0.dp,
                    color = if (focused) Palette.Accent else Color.Transparent
                )
                .onFocusChanged { st ->
                    focused = st.isFocused
                    if (st.isFocused) onFocused()
                }
                // Placeholders stay focusable but inert — non-focusable placeholders make
                // D-pad traversal skip a hole and jump unpredictably.
                .combinedClickable(
                    onClick = { if (item != null) onPlay() },
                    onLongClick = { if (item != null) onLongPress() }
                )
        ) {
            if (item?.imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.imageUrl)
                        // Downsample to the card's pixel size before caching.
                        .size(cardShape.widthPx, cardShape.heightPx)
                        .build(),
                    imageLoader = TvivoImageLoader.get(context),
                    contentDescription = item.title,
                    // Fit for channel logos, which must letterbox on the tile rather
                    // than lose their edges to a crop.
                    contentScale = when (cardShape) {
                        CardShape.POSTER -> ContentScale.Crop
                        CardShape.CHANNEL -> ContentScale.Fit
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // The panel lists the same title once per quality, and `nameDisplay` has the
            // quality token stripped out of it (that strip is what makes mixed-direction
            // titles truncate correctly). Without this badge the two rows render as the
            // same string and read as duplicates that the user cannot tell apart.
            // Top-start because the provider watermark on this panel's art sits top-end.
            item?.quality?.let { quality ->
                Text(
                    text = quality,
                    color = Palette.Ink,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Palette.Bg.copy(alpha = 0.78f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        Text(
            text = item?.title ?: "",
            color = if (focused) Palette.Ink else Palette.Dim,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

/** Card sizes are specified in pixels because they are image-pipeline inputs. */
private val CardShape.widthPx: Int
    get() = when (this) {
        CardShape.POSTER -> TvivoImageLoader.POSTER_WIDTH_PX
        CardShape.CHANNEL -> TvivoImageLoader.CHANNEL_WIDTH_PX
    }

private val CardShape.heightPx: Int
    get() = when (this) {
        CardShape.POSTER -> TvivoImageLoader.POSTER_HEIGHT_PX
        CardShape.CHANNEL -> TvivoImageLoader.CHANNEL_HEIGHT_PX
    }

@Composable
private fun CardShape.widthDp(): Dp = with(LocalDensity.current) { widthPx.toDp() }

@Composable
private fun CardShape.heightDp(): Dp = with(LocalDensity.current) { heightPx.toDp() }
