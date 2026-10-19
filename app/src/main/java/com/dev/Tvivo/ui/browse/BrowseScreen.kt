package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dev.Tvivo.R
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import com.dev.Tvivo.ui.common.HeaderIconButton
import com.dev.Tvivo.ui.common.IconPill
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import com.dev.Tvivo.ui.common.dpadFieldNavigation
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.SearchBackAction
import com.dev.Tvivo.ui.searchBackAction
import com.dev.Tvivo.home.CatalogSort
import com.dev.Tvivo.home.CatalogLandingPolicy
import com.dev.Tvivo.home.CatalogCategoryOrder
import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.repository.LandingCategoryMetrics
import com.dev.Tvivo.home.FocusLinkPolicy
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.adaptive.WindowSizeClass
import com.dev.Tvivo.ui.shell.LocalShellFocus
import com.dev.Tvivo.sync.CatalogSyncer
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler

@Composable
fun BrowseScreen(
    contentType: ContentType,
    accountId: String? = null,
    initialCategoryId: String? = null,
    initialItemFilter: String? = null,
    landingMode: Boolean = false,
    showSort: Boolean = landingMode,
    sort: CatalogSort = CatalogSort.MostVisited,
    onSortChanged: (CatalogSort) -> Unit = {},
    categorySort: CatalogSort? = null,
    onCategorySortChanged: ((CatalogSort) -> Unit)? = null,
    categoryMetrics: Map<String, LandingCategoryMetrics> = emptyMap(),
    onBackToLanding: () -> Unit = {},
    /** Activating a card. Opens the pre-run page; it never starts a stream. */
    onOpenDetail: (BrowseItem) -> Unit,
    /** The long-press shortcut only. Straight to the player, resume prompt included. */
    onPlay: (BrowseItem, Long) -> Unit
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    // Keyed by content type: Live and Movies are separate screens with separate
    // categories, selection and focus, and must not share one instance.
    val viewModel: BrowseViewModel = viewModel(
        key = "browse:${accountId ?: "stored"}:${contentType.name}",
        factory = viewModelFactory {
            initializer {
                BrowseViewModel(application, createSavedStateHandle(), contentType)
            }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val savedCategorySort by viewModel.categorySort.collectAsStateWithLifecycle()
    val effectiveCategorySort = categorySort ?: savedCategorySort
    val updateCategorySort = onCategorySortChanged ?: viewModel::setCategorySort
    var categoryApplied by remember(initialCategoryId) { mutableStateOf(false) }
    LaunchedEffect(initialCategoryId, state.visibleCategories) {
        if (!categoryApplied && initialCategoryId != null &&
            state.visibleCategories.any { it.categoryId == initialCategoryId }) {
            if (state.selectedCategoryId != initialCategoryId)
                viewModel.selectCategory(initialCategoryId, forceRecordVisit = landingMode)
            if (!initialItemFilter.isNullOrBlank() && state.itemFilter.isBlank()) {
                viewModel.setItemFilterOpen(true)
                viewModel.onItemFilterChanged(initialItemFilter)
            }
            categoryApplied = true
        }
    }
    val items = viewModel.items.collectAsLazyPagingItems()
    var contextMenuItem by remember { mutableStateOf<BrowseItem?>(null) }
    var resumePromptItem by remember { mutableStateOf<Pair<BrowseItem, Long>?>(null) }
    val scope = rememberCoroutineScope()
    val compactPhone = !LocalIsTelevision.current &&
        LocalWindowSizeClass.current == WindowSizeClass.Compact
    val categoryPickerMode = CategoryPickerMode.forEnvironment(
        LocalIsTelevision.current, LocalWindowSizeClass.current
    )
    var pickerModeSettled by remember(categoryPickerMode) { mutableStateOf(false) }
    LaunchedEffect(categoryPickerMode) {
        pickerModeSettled = false
        androidx.compose.runtime.withFrameNanos {}
        androidx.compose.runtime.withFrameNanos {}
        pickerModeSettled = true
    }
    val railAsPanel = compactPhone || landingMode
    var categoryPanelOpen by rememberSaveable { mutableStateOf(false) }
    val categoriesFocusRequester = remember { FocusRequester() }
    val gridFocusRequester = LocalShellFocus.current?.firstContent ?: remember { FocusRequester() }
    val railFocusRequester = remember { FocusRequester() }
    // Retain picker position when adaptive mode moves the rail between the page and drawer.
    val categoryListState = rememberLazyListState()
    val itemFilterFocusRequester = remember { FocusRequester() }
    val sortFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = landingMode && !state.isItemFilterOpen && !categoryPanelOpen) {
        onBackToLanding()
    }
    BackHandler(enabled = state.isItemFilterOpen) {
        // Back with text clears it and leaves the field open; empty Back closes it.
        if (searchBackAction(state.itemFilter) == SearchBackAction.Clear)
            viewModel.onItemFilterChanged("")
        else viewModel.setItemFilterOpen(false)
    }
    // Register after the screen's other handlers so an open picker owns Back even
    // when the item-filter state is also open. The picker is an in-screen modal; its
    // dismissal must never reach the shell's top-level navigation handler.
    BackHandler(enabled = railAsPanel && categoryPanelOpen) {
        categoryPanelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Back)
        scope.launch {
            androidx.compose.runtime.withFrameNanos {}
            runCatching { categoriesFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(categoryPanelOpen, categoryPickerMode) {
        if (categoryPanelOpen) {
            androidx.compose.runtime.withFrameNanos {}
            androidx.compose.runtime.withFrameNanos {}
            runCatching { railFocusRequester.requestFocus() }
        }
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
    var focusPlaced by remember(railAsPanel) { mutableStateOf(false) }
    val returningToItem = viewModel.pendingFocusItemId != null
    LaunchedEffect(state.visibleCategories.isNotEmpty(), returningToItem, railAsPanel, items.itemCount) {
        if (focusPlaced || returningToItem) return@LaunchedEffect
        if (state.visibleCategories.isNotEmpty()) {
            runCatching {
                if (landingMode && items.itemCount > 0) gridFocusRequester.requestFocus()
                else if (railAsPanel) categoriesFocusRequester.requestFocus()
                else railFocusRequester.requestFocus()
            }.onSuccess { focusPlaced = true }
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

    var edgeDrag by remember { mutableStateOf(false) }
    var dragDistance by remember { mutableStateOf(0f) }
    val swipeModifier = if (landingMode) Modifier.pointerInput(categoryPanelOpen) {
        detectHorizontalDragGestures(
            onDragStart = { edgeDrag = it.x < 40.dp.toPx(); dragDistance = 0f },
            onHorizontalDrag = { _, amount ->
                if (edgeDrag && !categoryPanelOpen) {
                    dragDistance += amount
                    if (dragDistance > 56.dp.toPx()) categoryPanelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Open)
                }
            }
        )
    } else Modifier
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize().background(AppColors.Bg)
        .then(if (LocalIsTelevision.current) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        .then(swipeModifier)) {
        if (!railAsPanel || (categoryPanelOpen && categoryPickerMode == CategoryPickerMode.FullScreen)) CategoryRail(
            // The *filtered* list. Counts stay keyed by id, so they follow whichever
            // categories survive the filter without any extra bookkeeping.
            categories = categoryPickerModel(state.virtualCategories, CatalogLandingPolicy.sorted(
                state.visibleCategories.map { category ->
                    val metric = categoryMetrics[category.categoryId]
                    CatalogCategoryOrder(category.categoryId, category.name, category.ordering,
                        metric?.visits ?: 0L, metric?.lastVisitedAt ?: 0L,
                        metric?.latestAdded ?: 0L,
                        normalizedName = NameNormalizer.of(category.name).normalized)
                }, effectiveCategorySort
            ).map { order -> state.visibleCategories.first { it.categoryId == order.id } },
                CategoryPickerEntryRoute.SeeAll),
            counts = state.counts,
            selectedCategoryId = state.selectedCategoryId,
            filter = state.categoryFilter,
            onFilterChanged = viewModel::onCategoryFilterChanged,
            onSelect = {
                if (!categoryPickerCanSelect(categoryPanelOpen, pickerModeSettled)) return@CategoryRail
                viewModel.selectCategory(it.categoryId)
                if (railAsPanel) {
                    categoryPanelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Select)
                    if (items.itemCount > 0) scope.launch {
                        repeat(5) {
                            androidx.compose.runtime.withFrameNanos {}
                            if (runCatching { gridFocusRequester.requestFocus() }.isSuccess)
                                return@launch
                        }
                    }
                }
            },
            gridFocusRequester = gridFocusRequester,
            railFocusRequester = railFocusRequester,
            listState = categoryListState,
            gridAvailable = items.itemCount > 0 && (!compactPhone || !categoryPanelOpen),
            categorySort = effectiveCategorySort,
            onCategorySortChanged = updateCategorySort,
            fullScreen = railAsPanel && categoryPanelOpen && categoryPickerMode == CategoryPickerMode.FullScreen,
            confineFocus = railAsPanel && categoryPanelOpen
        )

        // The rail has a fixed 280 dp width. `fillMaxSize()` here measured the content
        // against the whole Row and let its final grid column extend behind the screen
        // edge on a handset. Weight measures the remaining width after the rail instead.
        if (!railAsPanel || !categoryPanelOpen || categoryPickerMode == CategoryPickerMode.Overlay) Column(modifier = Modifier.weight(1f).fillMaxSize()) {
            if (landingMode) {
                Text(stringResource(R.string.browse_all_categories), color = AppColors.AccentText, style = TvType.label,
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusFrame()
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            if (native.action == android.view.KeyEvent.ACTION_DOWN &&
                                native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                runCatching { categoriesFocusRequester.requestFocus() }.isSuccess
                            } else false
                        }
                        .tvClickable { onBackToLanding() }
                        .padding(start = 24.dp, top = 12.dp, bottom = 12.dp))
            }
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
                refreshLabel = if (VirtualFolder.isVirtual(state.selectedCategoryId.orEmpty()))
                    stringResource(R.string.browse_refresh_library, stringResource(contentType.labelResource))
                    else stringResource(R.string.browse_refresh_category),
                itemFilter = state.itemFilter,
                isItemFilterOpen = state.isItemFilterOpen,
                filteredCount = state.filteredCount,
                totalCount = state.counts[state.selectedCategoryId],
                onItemFilterChanged = viewModel::onItemFilterChanged,
                onItemFilterOpenChanged = viewModel::setItemFilterOpen,
                gridFocusRequester = gridFocusRequester,
                itemFilterFocusRequester = itemFilterFocusRequester,
                railFocusRequester = railFocusRequester,
                onToggleCategories = if (railAsPanel && landingMode) ({
                    categoryPanelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Open)
                }) else null,
                categoriesFocusRequester = categoriesFocusRequester,
                showFilter = true,
                sort = sort,
                onSortChanged = onSortChanged,
                sortFocusRequester = sortFocusRequester,
                showSort = showSort && !LocalIsTelevision.current,
                railAvailable = !railAsPanel || categoryPanelOpen
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
                        onClear = { viewModel.onItemFilterChanged("") }
                    )

                items.itemCount == 0 &&
                    items.loadState.refresh !is androidx.paging.LoadState.Loading ->
                    EmptyState(onRefresh = viewModel::refreshSelected)

                else -> ContentGrid(
                    items = items,
                    pendingFocusItemId = viewModel.pendingFocusItemId,
                    cardShape = viewModel.cardShape,
                    gridFocusRequester = gridFocusRequester,
                    itemFilterFocusRequester = if (landingMode) sortFocusRequester
                        else itemFilterFocusRequester,
                    onActivate = { item ->
                        viewModel.pendingFocusItemId = item.id
                        onOpenDetail(item)
                    },
                    onContextMenu = { contextMenuItem = it },
                    onFocused = { viewModel.pendingFocusItemId = it.id },
                    progressFor = viewModel::progressFor,
                    onLeadingLeft = if (landingMode) ({ runCatching { categoriesFocusRequester.requestFocus() } }) else null
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
    CategoryPickerOverlay(
        visible = railAsPanel && categoryPanelOpen && categoryPickerMode == CategoryPickerMode.Overlay,
        onDismiss = {
            categoryPanelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Back)
            scope.launch {
                androidx.compose.runtime.withFrameNanos {}
                runCatching { categoriesFocusRequester.requestFocus() }
            }
        }
    ) { drawerModifier ->
        CategoryRail(
            categories = categoryPickerModel(state.virtualCategories, CatalogLandingPolicy.sorted(
                state.visibleCategories.map { category ->
                    val metric = categoryMetrics[category.categoryId]
                    CatalogCategoryOrder(category.categoryId, category.name, category.ordering,
                        metric?.visits ?: 0L, metric?.lastVisitedAt ?: 0L,
                        metric?.latestAdded ?: 0L,
                        normalizedName = NameNormalizer.of(category.name).normalized)
                }, effectiveCategorySort
            ).map { order -> state.visibleCategories.first { it.categoryId == order.id } },
                CategoryPickerEntryRoute.SeeAll),
            counts = state.counts, selectedCategoryId = state.selectedCategoryId,
            filter = state.categoryFilter, onFilterChanged = viewModel::onCategoryFilterChanged,
            onSelect = {
                if (!categoryPickerCanSelect(categoryPanelOpen, pickerModeSettled)) return@CategoryRail
                viewModel.selectCategory(it.categoryId)
                categoryPanelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Select)
                if (items.itemCount > 0) scope.launch {
                    repeat(5) {
                        androidx.compose.runtime.withFrameNanos {}
                        if (runCatching { gridFocusRequester.requestFocus() }.isSuccess)
                            return@launch
                    }
                }
            },
            gridFocusRequester = gridFocusRequester, railFocusRequester = railFocusRequester,
            listState = categoryListState,
            gridAvailable = false, categorySort = effectiveCategorySort,
            onCategorySortChanged = updateCategorySort,
            overlayDrawer = true, confineFocus = true, modifier = drawerModifier
        )
    }
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
    refreshLabel: String,
    itemFilter: String,
    isItemFilterOpen: Boolean,
    filteredCount: Int?,
    totalCount: Int?,
    onItemFilterChanged: (String) -> Unit,
    onItemFilterOpenChanged: (Boolean) -> Unit,
    gridFocusRequester: FocusRequester,
    itemFilterFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    onToggleCategories: (() -> Unit)?,
    categoriesFocusRequester: FocusRequester,
    showFilter: Boolean,
    sort: CatalogSort,
    onSortChanged: (CatalogSort) -> Unit,
    sortFocusRequester: FocusRequester,
    showSort: Boolean,
    railAvailable: Boolean
) {
    val openCategoriesDescription = stringResource(R.string.categories_open_accessibility)
    val refreshFocusRequester = remember { FocusRequester() }
    val horizontalMargin = when {
        LocalIsTelevision.current -> 32.dp
        LocalWindowSizeClass.current == WindowSizeClass.Compact -> 16.dp
        else -> 24.dp
    }
    val statusMessage = browseStatusMessage(error, syncState, hasCachedItems)
    val largeText = LocalConfiguration.current.fontScale >= 1.6f
    Column(modifier = Modifier.fillMaxWidth()) {
        if (onToggleCategories != null && filteredCount != null && totalCount != null) {
            Text(stringResource(R.string.browse_filtered_count, filteredCount, totalCount), color = AppColors.Dim, style = TvType.label,
                modifier = Modifier.padding(start = horizontalMargin, end = horizontalMargin))
        }
        if (largeText && !isItemFilterOpen) Text(title, color = AppColors.Ink,
            style = TvType.headline, modifier = Modifier.fillMaxWidth()
                .padding(horizontal = horizontalMargin))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalMargin, vertical = 16.dp),
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
                if (!isItemFilterOpen && !largeText) {
                    Text(
                        text = title,
                        color = AppColors.Ink,
                        style = TvType.headline,
                        // Two lines, never one: this panel ships `RAMADAN EGYPT 2026 SD`
                        // and `... HD`, which truncate to the same string and recreate
                        // the false-duplicate defect. Wrapping is the rule everywhere a
                        // category label is drawn.
                        softWrap = true
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
                countLine?.takeIf { onToggleCategories == null }?.let {
                    Text(text = it, color = AppColors.Dim, style = TvType.label)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (showSort && native.action == android.view.KeyEvent.ACTION_DOWN &&
                        native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                        runCatching { categoriesFocusRequester.requestFocus() }.isSuccess
                    } else false
                }
            ) {
                if (showSort && !LocalIsTelevision.current) CatalogSortMenu(
                    CatalogLandingPolicy.openedListSort(sort), onSortChanged,
                    sortFocusRequester, Modifier.padding(end = 10.dp)
                )
                if (onToggleCategories != null) {
                    HeaderIconButton(Icons.Default.Menu, openCategoriesDescription,
                        onClick = onToggleCategories,
                        modifier = Modifier.focusRequester(categoriesFocusRequester)
                            .testTag("category-picker-button"))
                }
                // Visible progress that dismisses at 100%. The rail counts filling in
                // category by category are the secondary signal.
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
                if (showFilter && isItemFilterOpen) {
                    ItemFilterField(
                        value = itemFilter,
                        onValueChange = onItemFilterChanged,
                        gridFocusRequester = gridFocusRequester,
                        itemFilterFocusRequester = itemFilterFocusRequester,
                        railFocusRequester = railFocusRequester,
                        refreshFocusRequester = refreshFocusRequester,
                        // Only a permanent rail is always attached; the grid only exists
                        // once there are rows (not loading / empty / no-match).
                        railAvailable = railAvailable,
                        gridAvailable = hasCachedItems
                    )
                } else if (showFilter) {
                    HeaderIconButton(
                        icon = Icons.Default.Search,
                        contentDescription = stringResource(R.string.browse_filter_items),
                        onClick = { onItemFilterOpenChanged(true) },
                        modifier = Modifier.padding(end = 12.dp).testTag("browse-filter")
                    )
                }

                if (!LocalIsTelevision.current) HeaderIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = refreshLabel,
                    enabled = !isRefreshing,
                    onClick = onRefresh,
                    modifier = Modifier.focusRequester(refreshFocusRequester)
                )
            }
        }

        if (syncState == "indexing") Text("Indexing $syncDone…", color = AppColors.Dim,
            style = TvType.label, modifier = Modifier.padding(horizontal = horizontalMargin))
        confirmation?.let { Text(it, color = AppColors.Dim, style = TvType.label,
            modifier = Modifier.padding(horizontal = horizontalMargin)) }
        statusMessage?.let { Text(stringResource(when {
                error == AppError.RateLimited -> R.string.browse_cached_rate_limited
                error == AppError.ServiceUnavailable -> R.string.browse_cached_service_unavailable
                error != null -> R.string.browse_cached_offline
                syncState == CatalogSyncer.STATE_PARTIAL -> R.string.browse_catalog_partial
                else -> R.string.browse_catalog_failed
            }), color = AppColors.Dim, style = TvType.label,
            modifier = Modifier.padding(horizontal = horizontalMargin)) }

        // Cache present and refreshing: a 3 px line under the header, never a blocking
        // spinner over content the user can already read.
        if (isRefreshing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalMargin)
                    .height(3.dp)
                    .background(AppColors.Accent)
            )
        }
    }
}

@Composable
private fun ErrorState(error: com.dev.Tvivo.data.AppError) {
    val copy = ErrorCopy.of(error)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = copy.message, color = AppColors.Ink, style = TvType.title)
            copy.primaryAction?.let {
                Text(
                    text = it,
                    color = AppColors.AccentText,
                    style = TvType.body,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

internal fun browseStatusMessage(error: AppError?, catalogSyncState: String?, hasCachedItems: Boolean): String? = when {
    error == AppError.RateLimited && hasCachedItems -> "Server busy — showing cached data. Try again shortly"
    error == AppError.ServiceUnavailable && hasCachedItems -> "Server unavailable — showing cached data"
    error != null && hasCachedItems -> "Offline — showing cached data"
    catalogSyncState == CatalogSyncer.STATE_PARTIAL -> "Catalog incomplete — showing available categories"
    catalogSyncState == CatalogSyncer.STATE_FAILED && hasCachedItems -> "Catalog update unavailable — showing cached data"
    else -> null
}

/**
 * D-14. The field the search pill turns into. Focus lands in it on open, so the pill and
 * the field are one gesture rather than two.
 *
 * **Back clears text first, then closes the empty field**. A filter that is
 * hidden but still applied is a grid quietly missing rows with nothing on screen to say
 * so.
 */
@Composable
internal fun ItemFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    gridFocusRequester: FocusRequester,
    itemFilterFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    refreshFocusRequester: FocusRequester,
    railAvailable: Boolean,
    gridAvailable: Boolean
) {
    val focus = itemFilterFocusRequester
    val clearFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val clearLabel = stringResource(R.string.browse_clear)
    LaunchedEffect(Unit) {
        repeat(5) {
            androidx.compose.runtime.withFrameNanos {}
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.browse_filter_hint), color = AppColors.Dim, style = TvType.caption) },
            singleLine = true,
            textStyle = TvType.body,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = AppColors.Ink,
                unfocusedTextColor = AppColors.Ink,
                focusedContainerColor = AppColors.Elevated,
                unfocusedContainerColor = AppColors.Elevated,
                cursorColor = AppColors.Accent,
                focusedBorderColor = AppColors.Focus,
                unfocusedBorderColor = AppColors.Line
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
                .testTag("item-filter-field")
                .width(FILTER_FIELD_WIDTH)
                .focusRequester(focus)
                .focusProperties {
                    left = FocusLinkPolicy.targetIfRendered(
                        railFocusRequester, railAvailable
                    ) ?: FocusRequester.Default
                    right = if (value.isNotEmpty()) clearFocusRequester else refreshFocusRequester
                    down = FocusLinkPolicy.targetIfRendered(
                        gridFocusRequester, gridAvailable
                    ) ?: FocusRequester.Default
                }
                .dpadFieldNavigation(focusManager)
        )
        // **Only once there is something to clear, and flush against the field.**
        // It used to render unconditionally with an 8 dp gap, so opening the filter put
        // a detached `✕ Clear filter` next to an empty box — a control offering to undo
        // something the user had not done yet, presented as though it belonged to the
        // header rather than to the field. Back already closes an empty filter, which is
        // the only thing Clear could have meant at that point.
        if (value.isNotEmpty()) {
            IconPill(
                icon = Icons.Default.Close,
                label = stringResource(R.string.browse_clear),
                onClick = { onValueChange(""); runCatching { focus.requestFocus() } },
                modifier = Modifier.testTag("item-filter-clear")
                    .semantics { contentDescription = clearLabel }
                    .focusRequester(clearFocusRequester)
                    .focusProperties { left = focus; right = refreshFocusRequester }
                    .padding(end = 12.dp)
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
        Text(text = "Loading…", color = AppColors.Dim, style = TvType.title)
    }
}

/** Distinct from [EmptyState]: the category has rows, this filter just does not match any. */
@Composable
private fun NoFilterMatchState(query: String, onClear: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Nothing here matches \"$query\"",
                color = AppColors.Ink,
                style = TvType.title
            )
            Text(
                text = "Clear filter",
                color = AppColors.AccentText,
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
            Text(text = "Nothing in this category", color = AppColors.Ink, style = TvType.title)
            Text(
                text = "Refresh",
                color = AppColors.AccentText,
                style = TvType.body,
                modifier = Modifier.tvClickable { onRefresh() }.padding(top = 16.dp)
            )
        }
    }
}
