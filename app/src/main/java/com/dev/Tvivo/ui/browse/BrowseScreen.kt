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
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(state.visibleCategories.isNotEmpty()) {
        if (!focusPlaced && state.visibleCategories.isNotEmpty()) {
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

        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                title = state.categories
                    .firstOrNull { it.categoryId == state.selectedCategoryId }
                    ?.name
                    .orEmpty(),
                isRefreshing = state.isRefreshing,
                confirmation = state.refreshConfirmation,
                syncState = state.catalogSyncState,
                syncDone = state.catalogSyncDone,
                onRefresh = viewModel::refreshSelected,
                itemFilter = state.itemFilter,
                isItemFilterOpen = state.isItemFilterOpen,
                filteredCount = state.filteredCount,
                totalCount = state.counts[state.selectedCategoryId],
                onItemFilterChanged = viewModel::onItemFilterChanged,
                onItemFilterOpenChanged = viewModel::setItemFilterOpen
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
    onRefresh: () -> Unit,
    itemFilter: String,
    isItemFilterOpen: Boolean,
    filteredCount: Int?,
    totalCount: Int?,
    onItemFilterChanged: (String) -> Unit,
    onItemFilterOpenChanged: (Boolean) -> Unit
) {
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

                // D-14 — `N of M`, and only while filtering. Unfiltered, `M` on its own
                // is the honest number and `48,751 of 48,751` is noise.
                val countLine = when {
                    filteredCount != null && totalCount != null ->
                        "$filteredCount of $totalCount"
                    totalCount != null -> "$totalCount"
                    else -> null
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
                        onClose = { onItemFilterOpenChanged(false) }
                    )
                } else {
                    IconPill(
                        icon = Icons.Default.Search,
                        label = "Filter this category",
                        onClick = { onItemFilterOpenChanged(true) },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }

                IconPill(
                    icon = Icons.Default.Refresh,
                    label = "Refresh this category",
                    enabled = !isRefreshing,
                    onClick = onRefresh
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
    onClose: () -> Unit
) {
    val focus = remember { FocusRequester() }
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
                .dpadFieldNavigation(focusManager)
        )
        IconPill(
            icon = Icons.Default.Close,
            label = "Clear filter",
            onClick = onClose,
            modifier = Modifier.padding(start = 8.dp, end = 12.dp)
        )
    }
}

private val FILTER_FIELD_WIDTH = 320.dp

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
