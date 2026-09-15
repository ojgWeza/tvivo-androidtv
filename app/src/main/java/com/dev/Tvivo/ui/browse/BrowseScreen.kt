package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.data.AppError
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import com.dev.Tvivo.ui.common.IconPill
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import com.dev.Tvivo.ui.common.dpadFieldNavigation
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.sync.CatalogSyncer
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler

@Composable
fun BrowseScreen(
    contentType: ContentType,
    /** Activating a card. Opens the pre-run page; it never starts a stream. */
    onOpenDetail: (BrowseItem) -> Unit,
    /** The long-press shortcut only. Straight to the player, resume prompt included. */
    onPlay: (BrowseItem, Long) -> Unit
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    // Keyed by content type: Live and Movies are separate screens with separate
    // categories, selection and focus, and must not share one instance.
    val viewModel: BrowseViewModel = viewModel(
        key = "browse:" + contentType.name,
        factory = viewModelFactory {
            initializer {
                BrowseViewModel(application, createSavedStateHandle(), contentType)
            }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    var contextMenuItem by remember { mutableStateOf<BrowseItem?>(null) }
    var resumePromptItem by remember { mutableStateOf<Pair<BrowseItem, Long>?>(null) }
    val scope = rememberCoroutineScope()
    val gridFocusRequester = remember { FocusRequester() }
    val railFocusRequester = remember { FocusRequester() }
    val itemFilterFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = state.isItemFilterOpen) {
        viewModel.setItemFilterOpen(false)
    }

    // **Q-25 — the screen must open with something focused.** It did not, and an
    // Android TV screen with no focus owner has no D-pad behaviour at all: the first
    // press runs a 2D search with no origin to measure from, so it lands on whichever
    // node the heuristic likes — which is how RIGHT out of the rail opened the search
    // field instead of crossing to the grid. The rail is the resting place because it
    // is where a user decides what to look at.
    //
    // Keyed on the first categories arriving: requesting focus on an empty LazyColumn
    // has nothing to delegate to. `runCatching` because a FocusRequester whose node has
    // left composition throws rather than returning false.
    //
    // **Returning from the pre-run page is not a fresh entry.** This screen recomposes
    // from scratch on the way back, so `focusPlaced` starts false again and the rail took
    // focus every time — which is why activating a card at grid position 800 and pressing
    // Back left the user on `RECENTLY ADDED` with the grid still showing the category they
    // came from. The rail and the header disagreed, and the grid position was unreachable
    // without scrolling to it again.
    //
    // `pendingFocusItemId` is the signal for which of the two entries this is: it is set
    // by `onActivate` on the way out and survives in `SavedStateHandle`. When it is
    // present the grid owns the restore ([ContentGrid] scrolls to the item and requests
    // focus onto it), so the rail must not compete for focus at all.
    var focusPlaced by remember { mutableStateOf(false) }
    val returningToItem = viewModel.pendingFocusItemId != null
    LaunchedEffect(state.visibleCategories.isNotEmpty(), returningToItem) {
        if (focusPlaced || returningToItem) return@LaunchedEffect
        if (state.visibleCategories.isNotEmpty()) {
            runCatching { railFocusRequester.requestFocus() }.onSuccess { focusPlaced = true }
        }
    }

    // The long-press menu's Play. If a resume point exists the prompt comes first, so
    // neither resuming nor starting over happens silently.
    //
    // OK on a card does **not** come through here any more — it opens the pre-run page,
    // which owns the same prompt. This is the shortcut, not the main path.
    fun launch(item: BrowseItem) {
        scope.launch {
            val resumeMs = viewModel.resumePositionMs(item.id)
            viewModel.pendingFocusItemId = item.id
            if (resumeMs != null && resumeMs > 0) {
                resumePromptItem = item to resumeMs
            } else {
                onPlay(item, 0L)
            }
        }
    }

    LaunchedEffect(state.refreshConfirmation) {
        if (state.refreshConfirmation != null) {
            delay(2000)
            viewModel.dismissRefreshConfirmation()
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        CategoryRail(
            // The *filtered* list. Counts stay keyed by id, so they follow whichever
            // categories survive the filter without any extra bookkeeping.
            categories = state.visibleCategories,
            counts = state.counts,
            selectedCategoryId = state.selectedCategoryId,
            filter = state.categoryFilter,
            onFilterChanged = viewModel::onCategoryFilterChanged,
            onSelect = { viewModel.selectCategory(it.categoryId) },
            gridFocusRequester = gridFocusRequester,
            railFocusRequester = railFocusRequester
        )

        // The rail has a fixed 280 dp width. `fillMaxSize()` here measured the content
        // against the whole Row and let its final grid column extend behind the screen
        // edge on a handset. Weight measures the remaining width after the rail instead.
        Column(modifier = Modifier.weight(1f).fillMaxSize()) {
            Header(
                // **Both lists, not just the panel's.** The three virtual folders are
                // `CategoryEntity` rows like any other, but they live in
                // `virtualCategories`, and this lookup searched `categories` alone — so
                // selecting RECENTLY ADDED, CONTINUE WATCHING or FAVOURITES matched
                // nothing and the header drew an empty title. Every content type opens on
                // a virtual folder, so that was the *first* thing the user saw on every
                // entry to every browse screen.
                //
                // The union rather than `visibleCategories`, which is filtered: the
                // selected category stays selected while the rail filter hides it, and a
                // title that disappeared as you typed would be a second version of the
                // same bug.
                title = (state.virtualCategories + state.categories)
                    .firstOrNull { it.categoryId == state.selectedCategoryId }
                    ?.name
                    .orEmpty(),
                isRefreshing = state.isRefreshing,
                confirmation = state.refreshConfirmation,
                syncState = state.catalogSyncState,
                syncDone = state.catalogSyncDone,
                error = state.error,
                hasCachedItems = items.itemCount > 0,
                onRefresh = viewModel::refreshSelected,
                itemFilter = state.itemFilter,
                isItemFilterOpen = state.isItemFilterOpen,
                filteredCount = state.filteredCount,
                totalCount = state.counts[state.selectedCategoryId],
                onItemFilterChanged = viewModel::onItemFilterChanged,
                onItemFilterOpenChanged = viewModel::setItemFilterOpen,
                gridFocusRequester = gridFocusRequester,
                itemFilterFocusRequester = itemFilterFocusRequester,
                railFocusRequester = railFocusRequester
            )

            when {
                // Offline is fully browsable: a dead network must fail at play time, not
                // throw a full-screen error over data the app already has.
                state.error != null && items.itemCount == 0 -> ErrorState(state.error!!)

                // Phase 5 — the first load of a cold category. Without this the screen
                // is blank while the query runs, which reads as an empty category that
                // then suddenly fills.
                items.itemCount == 0 &&
                    items.loadState.refresh is androidx.paging.LoadState.Loading ->
                    LoadingState()

                // A filter that matches nothing is not an empty category, and
                // offering `Refresh` for it would be answering a question nobody asked.
                items.itemCount == 0 &&
                    state.itemFilter.isNotBlank() &&
                    items.loadState.refresh !is androidx.paging.LoadState.Loading ->
                    NoFilterMatchState(
                        query = state.itemFilter,
                        onClear = { viewModel.setItemFilterOpen(false) }
                    )

                items.itemCount == 0 &&
                    items.loadState.refresh !is androidx.paging.LoadState.Loading ->
                    EmptyState(onRefresh = viewModel::refreshSelected)

                else -> ContentGrid(
                    items = items,
                    pendingFocusItemId = viewModel.pendingFocusItemId,
                    cardShape = viewModel.cardShape,
                    gridFocusRequester = gridFocusRequester,
                    itemFilterFocusRequester = itemFilterFocusRequester,
                    onActivate = { item ->
                        viewModel.pendingFocusItemId = item.id
                        onOpenDetail(item)
                    },
                    onContextMenu = { contextMenuItem = it },
                    onFocused = { viewModel.pendingFocusItemId = it.id }
                )
            }
        }
    }

    contextMenuItem?.let { item ->
        val isFavourite by (viewModel.isFavourite(item.id) ?: flowOf(false))
            .collectAsStateWithLifecycle(initialValue = false)
        var hasResume by remember(item.id) { mutableStateOf(false) }
        LaunchedEffect(item.id) {
            hasResume = (viewModel.resumePositionMs(item.id) ?: 0L) > 0
        }

        ItemContextMenu(
            item = item,
            isFavourite = isFavourite,
            hasResumePoint = hasResume,
            onDismiss = { contextMenuItem = null },
            onPlay = {
                contextMenuItem = null
                launch(item)
            },
            onToggleFavourite = {
                viewModel.toggleFavourite(item.id, !isFavourite)
                contextMenuItem = null
            },
            onClearResume = {
                viewModel.clearResume(item.id)
                contextMenuItem = null
            }
        )
    }

    resumePromptItem?.let { (item, positionMs) ->
        ResumePrompt(
            title = item.title,
            positionMs = positionMs,
            onResume = {
                resumePromptItem = null
                onPlay(item, positionMs)
            },
            onStartOver = {
                resumePromptItem = null
                onPlay(item, 0L)
            },
            onDismiss = { resumePromptItem = null }
        )
    }
}

@Composable
private fun Header(
    title: String,
    isRefreshing: Boolean,
    confirmation: String?,
    syncState: String?,
    syncDone: Int,
    error: AppError?,
    hasCachedItems: Boolean,
    onRefresh: () -> Unit,
    itemFilter: String,
    isItemFilterOpen: Boolean,
    filteredCount: Int?,
    totalCount: Int?,
    onItemFilterChanged: (String) -> Unit,
    onItemFilterOpenChanged: (Boolean) -> Unit,
    gridFocusRequester: FocusRequester,
    itemFilterFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester
) {
    val refreshFocusRequester = remember { FocusRequester() }
    val statusMessage = browseStatusMessage(error, syncState, hasCachedItems)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // **Q-21 — the title yields to the filter.** Opened, the field asks for
            // 320 dp that a `SpaceBetween` row has no spare, and nothing told the title
            // to shrink, so the field drew over it. `weight(1f)` measures the actions
            // first and hands the title whatever is left; the title itself steps aside
            // entirely while the filter is open, because someone filtering a category
            // already knows which category they are in, and the count line below is the
            // part that is actually changing under them.
            Column(modifier = Modifier.weight(1f)) {
                // The header shows the category name immediately, before any row arrives.
                if (!isItemFilterOpen) {
                    Text(
                        text = title,
                        color = Palette.Ink,
                        style = TvType.headline,
                        // Two lines, never one: this panel ships `RAMADAN EGYPT 2026 SD`
                        // and `... HD`, which truncate to the same string and recreate
                        // the false-duplicate defect. Wrapping is the rule everywhere a
                        // category label is drawn.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // **`N of M`, and *only* while filtering.** The unfiltered `M` used to
                // render too, which put a bare `100` under the title saying exactly what
                // the rail row two inches to the left already said — and on the virtual
                // folders, where the header draws no title, that bare number was the
                // whole header. A count is worth a line only when it is telling the user
                // something the rail cannot: how much of the category a filter just cut
                // away.
                val countLine = if (filteredCount != null && totalCount != null) {
                    "$filteredCount of $totalCount"
                } else {
                    null
                }
                countLine?.let {
                    Text(text = it, color = Palette.Dim, style = TvType.label)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Visible progress that dismisses at 100%. The rail counts filling in
                // category by category are the secondary signal.
                if (syncState == "indexing") {
                    Text(
                        text = "Indexing $syncDone…",
                        color = Palette.Dim,
                        style = TvType.label,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
                confirmation?.let {
                    Text(text = it, color = Palette.Dim, style = TvType.label,
                        modifier = Modifier.padding(end = 16.dp))
                }
                statusMessage?.let {
                    Text(text = it, color = Palette.Dim, style = TvType.label,
                        modifier = Modifier.padding(end = 16.dp))
                }
                // D-8 — the same component Home uses. This was a bare text link, which
                // made one global action look like two different things depending on
                // which screen you met it on. Reached by pressing UP from the grid's top
                // row.
                //
                // Note the scope difference the label has to carry: this refreshes the
                // *current category*, Home's pill refreshes everything.
                // D-14. Collapsed it is a search pill like any other; opened it
                // expands in place into a field with its own clear action, so the
                // control the user pressed is the control they end up typing into.
                if (isItemFilterOpen) {
                    ItemFilterField(
                        value = itemFilter,
                        onValueChange = onItemFilterChanged,
                        onClose = { onItemFilterOpenChanged(false) },
                        gridFocusRequester = gridFocusRequester,
                        itemFilterFocusRequester = itemFilterFocusRequester,
                        railFocusRequester = railFocusRequester,
                        refreshFocusRequester = refreshFocusRequester
                    )
                } else {
                    IconPill(
                        // "this category" was doing no work: the control sits inside the
                        // category, under its title, so the scope is already said by
                        // where the pill is. Spelling it out only made the expanded pill
                        // wide enough to shove the rest of the header sideways.
                        icon = Icons.Default.Search,
                        label = "Filter",
                        onClick = { onItemFilterOpenChanged(true) },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }

                IconPill(
                    icon = Icons.Default.Refresh,
                    label = "Refresh",
                    enabled = !isRefreshing,
                    onClick = onRefresh,
                    modifier = Modifier.focusRequester(refreshFocusRequester)
                )
            }
        }

        // Cache present and refreshing: a 3 px line under the header, never a blocking
        // spinner over content the user can already read.
        if (isRefreshing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(3.dp)
                    .background(Palette.Accent)
            )
        }
    }
}

@Composable
private fun ErrorState(error: com.dev.Tvivo.data.AppError) {
    val copy = ErrorCopy.of(error)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = copy.message, color = Palette.Ink, style = TvType.title)
            copy.primaryAction?.let {
                Text(
                    text = it,
                    color = Palette.AccentText,
                    style = TvType.body,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

internal fun browseStatusMessage(error: AppError?, catalogSyncState: String?, hasCachedItems: Boolean): String? = when {
    error != null && hasCachedItems -> "Offline — showing cached data"
    catalogSyncState == CatalogSyncer.STATE_PARTIAL -> "Catalog incomplete — showing available categories"
    catalogSyncState == CatalogSyncer.STATE_FAILED && hasCachedItems -> "Catalog update unavailable — showing cached data"
    else -> null
}

/**
 * D-14. The field the search pill turns into. Focus lands in it on open, so the pill and
 * the field are one gesture rather than two.
 *
 * **Back closes it and clears the filter** (see `setItemFilterOpen`). A filter that is
 * hidden but still applied is a grid quietly missing rows with nothing on screen to say
 * so.
 */
@Composable
private fun ItemFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
    gridFocusRequester: FocusRequester,
    itemFilterFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    refreshFocusRequester: FocusRequester
) {
    val focus = itemFilterFocusRequester
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Filter", color = Palette.Dim, style = TvType.caption) },
            singleLine = true,
            textStyle = TvType.body,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Palette.Ink,
                unfocusedTextColor = Palette.Ink,
                focusedContainerColor = Palette.Elevated,
                unfocusedContainerColor = Palette.Elevated,
                cursorColor = Palette.Accent,
                focusedBorderColor = Palette.Accent,
                unfocusedBorderColor = Palette.Line
            ),
            // Without this the field traps focus and the filtered grid is
            // unreachable — see `dpadFieldNavigation`.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                autoCorrect = false,
                imeAction = androidx.compose.ui.text.input.ImeAction.Next
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier
                .width(FILTER_FIELD_WIDTH)
                .focusRequester(focus)
                .focusProperties {
                    left = railFocusRequester
                    right = refreshFocusRequester
                    down = gridFocusRequester
                }
                .dpadFieldNavigation(focusManager)
        )
        // **Only once there is something to clear, and flush against the field.**
        // It used to render unconditionally with an 8 dp gap, so opening the filter put
        // a detached `✕ Clear filter` next to an empty box — a control offering to undo
        // something the user had not done yet, presented as though it belonged to the
        // header rather than to the field. Back already closes an empty filter, which is
        // the only thing Clear could have meant at that point.
        if (value.isNotBlank()) {
            IconPill(
                icon = Icons.Default.Close,
                label = "Clear",
                onClick = onClose,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
    }
}

/**
 * 260 dp, down from 320. The field only ever holds a few characters of a title — nobody
 * types a full name to filter a category — and at 320 dp it was wide enough to push the
 * refresh pill and the category title around it every time it opened.
 */
private val FILTER_FIELD_WIDTH = 260.dp

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Text, not a spinner: there is no progress to report for a single Room query, and
        // a spinner over a 200 ms wait is more distracting than a word.
        Text(text = "Loading…", color = Palette.Dim, style = TvType.title)
    }
}

/** Distinct from [EmptyState]: the category has rows, this filter just does not match any. */
@Composable
private fun NoFilterMatchState(query: String, onClear: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Nothing here matches \"$query\"",
                color = Palette.Ink,
                style = TvType.title
            )
            Text(
                text = "Clear filter",
                color = Palette.AccentText,
                style = TvType.body,
                modifier = Modifier
                    .tvFocusFrame()
                    .tvClickable { onClear() }
                    .padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(onRefresh: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Nothing in this category", color = Palette.Ink, style = TvType.title)
            Text(
                text = "Refresh",
                color = Palette.AccentText,
                style = TvType.body,
                modifier = Modifier.tvClickable { onRefresh() }.padding(top = 16.dp)
            )
        }
    }
}
