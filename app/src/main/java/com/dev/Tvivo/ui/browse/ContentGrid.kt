package com.dev.Tvivo.ui.browse

import android.view.KeyEvent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.launch
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dev.Tvivo.ui.common.TvivoImageLoader
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.adaptive.WindowSizeClass
import com.dev.Tvivo.data.repository.HomeCard

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
    /** Direct entry target when no card has been focused in this category yet. */
    firstCardFocusRequester: FocusRequester? = null,
    railFocusRequester: FocusRequester? = null,
    itemFilterFocusRequester: FocusRequester? = null,
    /** OK on a card. Opens the pre-run page — it has not started a stream since the
     *  detail screen landed, and the old name said otherwise. */
    onActivate: (BrowseItem) -> Unit,
    onContextMenu: (BrowseItem) -> Unit,
    onFocused: (BrowseItem) -> Unit,
    progressFor: suspend (BrowseItem) -> Float? = { null },
    onLeadingLeft: (() -> Unit)? = null,
    onGridFocused: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val restoreRequester = remember { FocusRequester() }
    var restored by remember(pendingFocusItemId) { mutableStateOf(pendingFocusItemId == null) }
    var focusedCardId by remember { mutableStateOf<Int?>(null) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var navigationTargetIndex by remember { mutableIntStateOf(-1) }
    var lastRemoteMoveAt by remember { mutableStateOf(Long.MIN_VALUE) }
    var remoteMoveInProgress by remember { mutableStateOf(false) }
    val remoteScope = rememberCoroutineScope()
    val television = LocalIsTelevision.current

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
            runCatching { restoreRequester.requestFocus() }
            if (focusedCardId == pendingFocusItemId) {
                restored = true
                return@LaunchedEffect
            }
        }
        // Give up on the focus, keep the scroll: a wrong-but-visible position beats
        // fighting for focus forever.
        restored = true
    }

    // Decode dimensions stay fixed for the image cache; cell width and column count
    // adapt to the available TV, tablet, or handset width.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = when {
            LocalIsTelevision.current -> 32.dp
            LocalWindowSizeClass.current == WindowSizeClass.Compact -> 16.dp
            else -> 24.dp
        }
        val gridGap = 16.dp
        val minCellWidth = if (cardShape == CardShape.POSTER) 150.dp else 200.dp
        val columns = ((maxWidth - horizontalPadding * 2 + gridGap) / (minCellWidth + gridGap))
            .toInt()
            .coerceAtLeast(1)

        Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minCellWidth),
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
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(gridFocusRequester)
            .onFocusChanged { if (it.hasFocus) onGridFocused() }
            .focusProperties {
                enter = {
                    runCatching {
                        when {
                            restoreTargetIsLaidOut() -> restoreRequester
                            firstCardFocusRequester != null && gridState.layoutInfo.visibleItemsInfo.any {
                                it.index == items.itemSnapshotList.placeholdersBefore &&
                                    items.peek(it.index) != null
                            } -> firstCardFocusRequester
                            else -> FocusRequester.Default
                        }
                    }.getOrDefault(FocusRequester.Default)
                }
                exit = { direction ->
                    if (television && direction == FocusDirection.Left && railFocusRequester != null)
                        railFocusRequester else FocusRequester.Default
                }
            }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (!television) return@onPreviewKeyEvent false
                if (native.action == KeyEvent.ACTION_UP &&
                    native.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                    lastRemoteMoveAt = Long.MIN_VALUE
                    return@onPreviewKeyEvent false
                }
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                if (remoteMoveInProgress) return@onPreviewKeyEvent true
                val move = TvRemoteNavigationPolicy.move(native.keyCode, television,
                    native.repeatCount, native.eventTime - native.downTime)
                val pagingMove = move is TvGridMove.Page || move == TvGridMove.First || move == TvGridMove.Last
                val heldMove = move is TvGridMove.HeldRows
                if ((!pagingMove && !heldMove) ||
                    (native.repeatCount > 0 && !TvRemoteNavigationPolicy.acceptRepeat(native.eventTime, lastRemoteMoveAt)))
                    return@onPreviewKeyEvent false
                lastRemoteMoveAt = native.eventTime
                val visible = gridState.layoutInfo.visibleItemsInfo
                val columns = visible.maxOfOrNull { it.column + 1 }?.coerceAtLeast(1) ?: 1
                val viewportRows = ((gridState.layoutInfo.viewportSize.height * 0.85f) /
                    (visible.firstOrNull()?.size?.height?.coerceAtLeast(1) ?: 1)).toInt().coerceAtLeast(1)
                val firstLoaded = items.itemSnapshotList.placeholdersBefore
                val lastLoaded = (firstLoaded + items.itemSnapshotList.items.size - 1).coerceAtLeast(firstLoaded)
                val target = when (move) {
                    is TvGridMove.Page -> focusedIndex + move.direction * viewportRows * columns
                    TvGridMove.First -> firstLoaded
                    TvGridMove.Last -> lastLoaded
                    is TvGridMove.HeldRows -> focusedIndex + move.direction *
                        (if (move.rows == 0) viewportRows else move.rows) * columns
                    TvGridMove.Ignore -> focusedIndex
                }.coerceIn(firstLoaded, lastLoaded)
                val direction = when (move) {
                    is TvGridMove.Page -> move.direction
                    is TvGridMove.HeldRows -> move.direction
                    else -> 1
                }
                val loadedTarget = generateSequence(target) { it + if (it <= focusedIndex) -1 else 1 }
                    .takeWhile { it in firstLoaded..lastLoaded }
                    .firstOrNull { items.peek(it) != null } ?: return@onPreviewKeyEvent false
                navigationTargetIndex = loadedTarget
                remoteMoveInProgress = true
                remoteScope.launch {
                    try {
                        gridState.scrollToItem(loadedTarget)
                        repeat(6) {
                            withFrameNanos {}
                            if (runCatching { restoreRequester.requestFocus() }.isSuccess) return@launch
                        }
                    } finally {
                        remoteMoveInProgress = false
                        navigationTargetIndex = -1
                    }
                }
                direction != 0
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = horizontalPadding,
            top = 16.dp,
            end = horizontalPadding,
            bottom = if (LocalIsTelevision.current) 16.dp else 48.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(gridGap),
        verticalArrangement = Arrangement.spacedBy(gridGap)
        ) {
            items(
            count = items.itemCount,
            // Without stable keys, recomposition after an append rebinds focus onto the
            // wrong item.
            key = items.itemKey { "content:${it.id}" }
            ) { index ->
                val item = items[index]
                val isRestoreTarget = item != null && item.id == lastFocusedId

                StreamCard(
                    item = item,
                    cardShape = cardShape,
                    modifier = (if (isRestoreTarget || index == navigationTargetIndex) {
                        Modifier.focusRequester(restoreRequester)
                    } else {
                    Modifier
                }).then(if (index == items.itemSnapshotList.placeholdersBefore && item != null &&
                    firstCardFocusRequester != null)
                    Modifier.focusRequester(firstCardFocusRequester) else Modifier)
                    .then(if (onLeadingLeft != null &&
                    (index % columns == 0 || index == gridState.firstVisibleItemIndex))
                    Modifier.onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                            native.action == KeyEvent.ACTION_DOWN) {
                            onLeadingLeft()
                            true
                        } else false
                    } else Modifier),
                    itemFilterFocusRequester = itemFilterFocusRequester.takeIf { index < columns },
                    onActivate = { item?.let(onActivate) },
                    onLongPress = { item?.let(onContextMenu) },
                    onFocused = {
                        item?.let {
                            focusedIndex = index
                            if (index == navigationTargetIndex) navigationTargetIndex = -1
                            lastFocusedId = it.id
                            focusedCardId = it.id
                            onFocused(it)
                        }
                    },
                    progressFor = progressFor
                )
            }

            if (items.loadState.append is androidx.paging.LoadState.Loading) {
                item {
                    // The loading footer must not be focusable, or the user D-pads onto a
                    // spinner and cannot get past it.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(cardShape.aspectRatio())
                            .focusProperties { canFocus = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("…", color = AppColors.Dim)
                    }
                }
            }
        }
        if (television) Text(stringResource(com.dev.Tvivo.R.string.remote_page_hint),
            color = AppColors.Dim, style = TvType.caption,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = horizontalPadding))
        }
    }
}

/** Search cards share the same decoded image sizes, title treatment, and focus surface as Browse. */
@Composable
internal fun SearchContentCard(
    card: HomeCard,
    onActivate: (HomeCard) -> Unit,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {}
) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        StreamCard(
            item = card.item,
            cardShape = if (card.type == com.dev.Tvivo.ui.home.ContentType.LIVE)
                CardShape.CHANNEL else CardShape.POSTER,
            modifier = Modifier.widthIn(max = searchCardMaxWidthDp(maxWidth.value.toInt()).dp)
                .fillMaxWidth().testTag("search-result-${card.key}"),
            focusRequester = focusRequester,
            onActivate = { onActivate(card) },
            onLongPress = { onActivate(card) },
            onFocused = onFocused,
            progressFor = { card.progress }
        )
    }
}

internal fun searchCardMaxWidthDp(columnWidthDp: Int): Int = columnWidthDp.coerceAtMost(220)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamCard(
    item: BrowseItem?,
    cardShape: CardShape,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    itemFilterFocusRequester: FocusRequester? = null,
    onActivate: () -> Unit,
    onLongPress: () -> Unit,
    onFocused: () -> Unit,
    progressFor: suspend (BrowseItem) -> Float?
) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val largeText = LocalConfiguration.current.fontScale >= 1.6f
    val progress by produceState<Float?>(initialValue = null, item?.id) {
        value = item?.let { progressFor(it) }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(cardShape.aspectRatio())
                .testTag("catalog-item-${item?.id ?: "placeholder"}")
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .background(AppColors.Elevated)
                // The shared focus frame is static: no scale or lazy-row clipping risk.
                .tvFocusFrame()
                .onFocusChanged { st ->
                    focused = st.isFocused
                    if (st.isFocused) onFocused()
                }
                .focusProperties {
                    itemFilterFocusRequester?.let { up = it }
                }
                // Placeholders stay focusable but inert — non-focusable placeholders make
                // D-pad traversal skip a hole and jump unpredictably.
                .combinedClickable(
                    onClick = { if (item != null) onActivate() },
                    onLongClick = { if (item != null) onLongPress() }
                )
        ) {
            if (!item?.imageUrl.isNullOrBlank()) {
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
            } else if (item != null) {
                com.dev.Tvivo.ui.common.ArtworkFallback(
                    title = item.title,
                    channel = cardShape == CardShape.CHANNEL,
                    showTitle = false
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
                    modifier = Modifier
                        .align(if (cardShape == CardShape.CHANNEL) Alignment.BottomStart else Alignment.TopStart)
                        .padding(start = if (cardShape == CardShape.CHANNEL) 8.dp else 0.dp,
                            bottom = if (cardShape == CardShape.CHANNEL) 8.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Accent-tinted so it reads as a value rather than a label, and so it
                    // is distinguishable from the quality token at a glance across a grid.
                    item.rating?.let { rating ->
                        Text(
                            text = rating.asRatingLabel(),
                            color = AppColors.MediaAccent,
                            style = TvType.caption,
                            maxLines = 1,
                            modifier = Modifier
                                .background(AppColors.MediaBase.copy(alpha = 0.88f))
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
                            color = AppColors.MediaText,
                            style = TvType.caption,
                            maxLines = 1,
                            modifier = Modifier
                                .background(AppColors.MediaBase.copy(alpha = 0.88f))
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
                        .height(if (largeText) 118.dp else SCRIM_HEIGHT)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.30f to AppColors.MediaBase.copy(alpha = 0.55f),
                                0.55f to AppColors.MediaBase.copy(alpha = 0.88f),
                                1f to AppColors.MediaBase.copy(alpha = 0.98f)
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
                        color = AppColors.MediaText,
                        style = TvType.label.copy(lineBreak = LineBreak.Heading),
                        maxLines = 2,
                        minLines = 2,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            progress?.let { fraction ->
                Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomStart)
                    .background(AppColors.Line)) {
                    Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(AppColors.Accent))
                }
            }
        }

        // Live keeps its title below the card: the 220x124 channel tile is too short to
        // give up its bottom to a scrim, and channel logos put their mark dead centre
        // where an overlay would land.
        if (cardShape == CardShape.CHANNEL) {
            Text(
                text = item?.title ?: "",
                color = if (focused) AppColors.Ink else AppColors.Dim,
                style = TvType.label.copy(lineBreak = LineBreak.Heading),
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    // Fixed two-line box. Roboto and the Noto Naskh Arabic fallback have
                    // different metrics, so a row of one- and two-line titles would
                    // otherwise change height as the user scrolls through it.
                    .height(if (largeText) 84.dp else TITLE_BLOCK_HEIGHT)
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

private fun CardShape.aspectRatio(): Float = when (this) {
    CardShape.POSTER -> 2f / 3f
    CardShape.CHANNEL -> 16f / 9f
}

/** Decode sizes are specified in pixels because they are image-pipeline inputs. */
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
