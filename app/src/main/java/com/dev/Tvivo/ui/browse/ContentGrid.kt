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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dev.Tvivo.ui.common.TvivoImageLoader
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/**
 * The one grid, for every content type. Four things live here rather than in hardening,
 * because everything reuses them: placeholders on with stable keys, focus restoration by
 * item ID, the static focus frame, and the long-press menu.
 *
 * [cardShape] is the only thing live changes — a 16:9 channel tile instead of a 2:3
 * poster. It is a pixel size fed to the image pipeline, not styling; see
 * [TvivoImageLoader].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContentGrid(
    items: LazyPagingItems<BrowseItem>,
    pendingFocusItemId: Int?,
    cardShape: CardShape,
    /** The rail's RIGHT target; a focus group forwards it to its first focusable card. */
    gridFocusRequester: FocusRequester,
    /** OK on a card. Opens the pre-run page — it has not started a stream since the
     *  detail screen landed, and the old name said otherwise. */
    onActivate: (BrowseItem) -> Unit,
    onContextMenu: (BrowseItem) -> Unit,
    onFocused: (BrowseItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val restoreRequester = remember { FocusRequester() }
    var restored by remember(pendingFocusItemId) { mutableStateOf(pendingFocusItemId == null) }

    /**
     * The card focus should come back to when focus **re-enters** the grid from outside
     * — the rail, or the header.
     *
     * Unlike `pendingFocusItemId` (a `SavedStateHandle` value, deliberately not
     * observable, read once per fresh composition) this is Compose state, because
     * `restoreRequester` has to *move* to whichever card the user last stood on while
     * the screen stays composed. Seeded from the pending id so a return from the pre-run
     * page and a return from the rail converge on the same card.
     */
    var lastFocusedId by remember { mutableStateOf(pendingFocusItemId) }

    /**
     * Whether [restoreRequester] currently has a node, answered from the grid's own
     * layout rather than tracked in a flag.
     *
     * `FocusRequester.requestFocus()` throws when nothing is attached and the focus system
     * calls it *inside* the `enter` lambda, where nothing can catch it — so `enter` must
     * not hand it over unless the target card is really composed.
     *
     * The first attempt tracked this with a boolean set by a `DisposableEffect` on the
     * target card, and it did not work: moving focus from one card to the next runs the
     * new card's effect and the old card's `onDispose` in an order Compose does not
     * guarantee, so the dispose regularly landed last and left the flag false. Focus then
     * fell through to the first child on every re-entry — the exact bug this is here to
     * fix. `visibleItemsInfo` is the laid-out truth, read at the moment it is needed, with
     * no ordering to get wrong.
     */
    fun restoreTargetIsLaidOut(): Boolean {
        val id = lastFocusedId ?: return false
        return gridState.layoutInfo.visibleItemsInfo.any { info ->
            info.index < items.itemCount && items.peek(info.index)?.id == id
        }
    }

    // Restoration is by stable item ID with a nearest-index fallback, never by raw index:
    // the list can have shifted under us while a stream was playing.
    //
    // **Scrolling to the item was never enough.** This used to stop at `scrollToItem`,
    // which put the card on screen but left focus wherever the screen had placed it — on
    // the rail — so coming back from the pre-run page showed the right grid position and
    // then made the user walk back into it. The scroll and the focus have to happen
    // together or the restore is only half done.
    //
    // The `requestFocus` cannot run in the same frame as the scroll: `restoreRequester` is
    // attached to a card that is not composed until the scroll has been laid out, and a
    // FocusRequester with no node throws. Hence the bounded frame-by-frame retry rather
    // than a single call — bounded because a pending id can legitimately no longer be in
    // the list at all (the catalog re-synced under us), and an unbounded wait for a card
    // that will never arrive would spin for the life of the screen.
    LaunchedEffect(pendingFocusItemId, items.itemCount) {
        if (restored || pendingFocusItemId == null || items.itemCount == 0) return@LaunchedEffect
        val index = (0 until items.itemCount).firstOrNull { i ->
            items.peek(i)?.id == pendingFocusItemId
        } ?: return@LaunchedEffect
        gridState.scrollToItem(index)
        repeat(FOCUS_RESTORE_FRAMES) {
            withFrameNanos {}
            if (runCatching { restoreRequester.requestFocus() }.isSuccess) {
                restored = true
                return@LaunchedEffect
            }
        }
        // Give up on the focus, keep the scroll: a wrong-but-visible position beats
        // fighting for focus forever.
        restored = true
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = gridState,
        // **This is what makes leaving to the rail and coming back land where the user
        // left.** A focus group entered from outside delegates to its *first* focusable
        // child, so LEFT to the rail then RIGHT back jumped from wherever the user was to
        // item 0.
        //
        // `focusProperties { enter = … }` rather than `focusRestorer()`: the latter reads
        // like the purpose-built answer, but combined with an explicit `focusGroup()` on
        // the same chain it dropped focus off the screen entirely — the grid was entered,
        // the remembered child was not restored, and nothing ended up focused, which on a
        // D-pad device means the remote stops working until the user backs out. `enter` is
        // explicit about where focus goes and degrades to `Default` (first child, the old
        // behaviour) whenever the target is not attached.
        modifier = modifier
            .fillMaxSize()
            .focusRequester(gridFocusRequester)
            .focusProperties {
                enter = {
                    if (restoreTargetIsLaidOut()) restoreRequester else FocusRequester.Default
                }
            }
            .focusGroup(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 48.dp),
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
            val isRestoreTarget = item != null && item.id == lastFocusedId

            StreamCard(
                item = item,
                cardShape = cardShape,
                modifier = if (isRestoreTarget) {
                    Modifier.focusRequester(restoreRequester)
                } else {
                    Modifier
                },
                onActivate = { item?.let(onActivate) },
                onLongPress = { item?.let(onContextMenu) },
                onFocused = {
                    item?.let {
                        lastFocusedId = it.id
                        onFocused(it)
                    }
                }
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
    onActivate: () -> Unit,
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
                    onClick = { if (item != null) onActivate() },
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
            } else if (item != null && cardShape == CardShape.CHANNEL) {
                // A missing logo is panel data, not an empty card. Preserve the artwork
                // bounds and give the channel a legible, stable fallback at ten feet.
                Text(
                    text = item.title.firstOrNull()?.uppercase() ?: "•",
                    color = Palette.Ink,
                    style = TvType.headline,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.Center)
                )

            }
            // **Both corner badges share one row, top-start.** The provider watermark on
            // this panel's art sits top-end, so that corner is unusable, and two
            // independently-aligned badges in the same corner would overlap on any card
            // carrying both. A single row lays them out side by side instead.
            //
            // Rating first: it is the one the eye is looking for, and quality is the
            // disambiguator that only matters once two rows read alike.
            if (item?.rating != null || item?.quality != null) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Accent-tinted so it reads as a value rather than a label, and so it
                    // is distinguishable from the quality token at a glance across a grid.
                    item.rating?.let { rating ->
                        Text(
                            text = rating.asRatingLabel(),
                            color = Palette.Accent,
                            style = TvType.caption,
                            maxLines = 1,
                            modifier = Modifier
                                .background(Palette.Bg.copy(alpha = 0.78f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    // The panel lists the same title once per quality, and `nameDisplay`
                    // has the quality token stripped out of it (that strip is what makes
                    // mixed-direction titles truncate correctly). Without this badge the
                    // two rows render as the same string and read as duplicates that the
                    // user cannot tell apart.
                    item.quality?.let { quality ->
                        Text(
                            text = quality,
                            color = Palette.Ink,
                            style = TvType.caption,
                            maxLines = 1,
                            modifier = Modifier
                                .background(Palette.Bg.copy(alpha = 0.78f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // **D-6 — poster titles are overlaid, not placed underneath.** Underneath
            // costs ~90 px of every card on every row and drops the grid from 2.46
            // visible rows to 1.85. Overlaying costs the bottom ~15% of the artwork
            // instead, which is affordable only because the quality badge is a separate
            // element top-start and does not compete with it. This is what closes Q-8.
            if (cardShape == CardShape.POSTER) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        // **Q-18 — the scrim owns its own height.** The first cut sized
                        // the box to the text plus 6 dp and started the gradient at 45%
                        // of that, which left almost no distance to fade over: titles
                        // over bright posters ended up competing with the artwork rather
                        // than sitting on it. The scrim is now a fixed band, taller than
                        // two lines of `label`, so the gradient has room to be fully
                        // opaque behind the text and fully clear well above it.
                        .height(SCRIM_HEIGHT)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.30f to Palette.Bg.copy(alpha = 0.55f),
                                0.55f to Palette.Bg.copy(alpha = 0.88f),
                                1f to Palette.Bg.copy(alpha = 0.98f)
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    // Text pinned to the bottom of the band, so a one-line title sits in
                    // the opaque part rather than floating in the middle of the fade.
                    contentAlignment = Alignment.BottomStart
                ) {
                    Text(
                        text = item?.title ?: "",
                        // Always full-strength ink: the title sits on its own scrim, so
                        // dimming it unfocused would fight the scrim rather than the
                        // artwork, and the focus frame already says where focus is.
                        color = Palette.Ink,
                        style = TvType.label,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Live keeps its title below the card: the 220x124 channel tile is too short to
        // give up its bottom to a scrim, and channel logos put their mark dead centre
        // where an overlay would land.
        if (cardShape == CardShape.CHANNEL) {
            Text(
                text = item?.title ?: "",
                color = if (focused) Palette.Ink else Palette.Dim,
                style = TvType.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    // Fixed two-line box. Roboto and the Noto Naskh Arabic fallback have
                    // different metrics, so a row of one- and two-line titles would
                    // otherwise change height as the user scrolls through it.
                    .height(TITLE_BLOCK_HEIGHT)
                    .padding(top = 8.dp)
            )
        }
    }
}

/** Two lines of `label` (20 sp line height) plus its 8 dp offset from the card. */
private val TITLE_BLOCK_HEIGHT = 48.dp

/**
 * The poster title band. Two lines of `label` need 40 dp; the rest is fade distance, and
 * it is the fade distance that Q-18 was short of. At 68 dp on a 165 dp poster this is
 * ~41% of the card, but only the bottom ~25% is meaningfully darkened.
 */
private val SCRIM_HEIGHT = 68.dp

/**
 * How many frames the focus restore waits for its card to compose before giving up.
 *
 * Eight is a tenth of a second at 60 Hz — long enough for a `scrollToItem` plus layout on
 * the slowest box this targets, short enough that a pending id which no longer exists
 * costs nothing anyone can perceive.
 */
private const val FOCUS_RESTORE_FRAMES = 8

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
