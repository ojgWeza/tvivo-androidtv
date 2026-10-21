package com.dev.Tvivo.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import android.view.KeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.R
import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.repository.HomeCard
import com.dev.Tvivo.data.repository.HomeRepository
import com.dev.Tvivo.data.repository.HomeShelf
import com.dev.Tvivo.data.repository.LandingCategoryMetrics
import com.dev.Tvivo.home.CatalogCategoryOrder
import com.dev.Tvivo.home.CatalogLandingPolicy
import com.dev.Tvivo.home.LandingSpec
import com.dev.Tvivo.home.CatalogSearchMode
import com.dev.Tvivo.home.CatalogSort
import com.dev.Tvivo.home.ShelfContentType
import com.dev.Tvivo.home.ShelfLoadState
import com.dev.Tvivo.home.ShelfRestoreKey
import com.dev.Tvivo.home.ShelfVerticalDirection
import com.dev.Tvivo.home.VirtualFolderKey
import com.dev.Tvivo.ui.common.dpadFieldNavigation
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.HeaderIconButton
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.common.screenHeadingRequester
import com.dev.Tvivo.ui.components.ShelfRow
import com.dev.Tvivo.ui.components.LandingScaffold
import com.dev.Tvivo.ui.components.landingContentPadding
import com.dev.Tvivo.ui.components.LandingLoadingSkeleton
import com.dev.Tvivo.ui.components.ShelfFocusRegistry
import com.dev.Tvivo.ui.components.navigateBetweenShelves
import com.dev.Tvivo.ui.components.navigateShelfPage
import com.dev.Tvivo.ui.shell.LocalShellFocus
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The tab entry is a lazy list of SQL-bounded previews. A full type stays in Room paging. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogLandingScreen(
    contentType: ContentType,
    accountId: String,
    initialCategoryId: String? = null,
    onOpenedCategoryChanged: (String?) -> Unit = {},
    shellSortChoice: CatalogSort = CatalogSort.MostVisited,
    shellSortRevision: Int = 0,
    shellRefreshRevision: Int = 0,
    onShellSortStateChanged: (CatalogSort) -> Unit = {},
    pendingFocusCardKey: String? = null,
    onPendingFocusConsumed: () -> Unit = {},
    onReturnToLanding: () -> Unit = {},
    onOpenDetail: (BrowseItem, String) -> Unit,
    onPlay: (BrowseItem, Long, String) -> Unit,
    onOpenDetailFromGrid: (BrowseItem) -> Unit = { item -> onOpenDetail(item, "") },
    onPlayFromGrid: (BrowseItem, Long) -> Unit = { item, position -> onPlay(item, position, "") }
) {
    if (LocalIsTelevision.current) {
        BrowseScreen(
            contentType = contentType,
            accountId = accountId,
            initialCategoryId = initialCategoryId,
            onBackToLanding = onReturnToLanding,
            onOpenDetail = onOpenDetailFromGrid,
            onPlay = onPlayFromGrid
        )
        return
    }
    val openCategoriesDescription = stringResource(R.string.categories_open_accessibility)
    val contentTypeLabel = stringResource(contentType.labelResource)
    val application = LocalContext.current.applicationContext as android.app.Application
    val viewModel: BrowseViewModel = viewModel(
        key = "browse:$accountId:${contentType.name}",
        factory = viewModelFactory {
            initializer { BrowseViewModel(application, createSavedStateHandle(), contentType) }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val repository = remember(accountId) { HomeRepository(AppDatabase.get(application), accountId) }
    val landingSpec = remember(contentType) { LandingSpec.forType(contentType.toShelfContentType()) }
    val openCategoryId = initialCategoryId
    var openItemFilter by rememberSaveable(accountId, contentType) { mutableStateOf<String?>(null) }
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val categorySort by viewModel.categorySort.collectAsStateWithLifecycle()
    val query = ""
    var panelOpen by rememberSaveable(accountId, contentType) { mutableStateOf(false) }
    var panelFilter by rememberSaveable(accountId, contentType) { mutableStateOf("") }
    var panelSelectedId by rememberSaveable(accountId, contentType) { mutableStateOf(VirtualFolder.ALL.id) }
    var lastFocusedShelfId by rememberSaveable(accountId, contentType) { mutableStateOf<String?>(null) }
    var lastFocusWasSeeAll by rememberSaveable(accountId, contentType) { mutableStateOf(false) }
    var restoreSeeAll by remember { mutableStateOf(false) }
    var restoreLandingCard by remember { mutableStateOf(false) }
    var pinned by remember(accountId, contentType) { mutableStateOf<List<HomeShelf>>(emptyList()) }
    var pinnedLoaded by remember(accountId, contentType) { mutableStateOf(false) }
    var metrics by remember { mutableStateOf<Map<String, LandingCategoryMetrics>>(emptyMap()) }
    val matchingCounts = emptyMap<String, Int>()
    var landingReload by remember { mutableIntStateOf(0) }
    var contextMenuCard by remember { mutableStateOf<HomeCard?>(null) }
    var resumePromptCard by remember { mutableStateOf<Pair<HomeCard, Long>?>(null) }
    val scope = rememberCoroutineScope()
    var showRefreshFeedback by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val categoriesFocus = remember { FocusRequester() }
    val pickerMode = CategoryPickerMode.forEnvironment(
        LocalIsTelevision.current, LocalWindowSizeClass.current
    )
    var pickerModeSettled by remember(pickerMode) { mutableStateOf(false) }
    LaunchedEffect(pickerMode) {
        pickerModeSettled = false
        androidx.compose.runtime.withFrameNanos {}
        androidx.compose.runtime.withFrameNanos {}
        pickerModeSettled = true
    }
    val television = LocalIsTelevision.current
    val landingRefreshFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // The picker swaps between a full-screen page and an overlay at the window-size
    // breakpoint. Keep its scroll state above those mutually exclusive branches.
    val categoryListState = rememberLazyListState()
    val shelfFocusRegistry = remember { ShelfFocusRegistry() }
    val screenHeadingRequester = remember { BringIntoViewRequester() }
    val shellFocus = LocalShellFocus.current
    val hasCachedCatalog = state.categories.isNotEmpty() || state.counts.values.any { it > 0 }

    LaunchedEffect(shellSortRevision) {
        if (shellSortRevision > 0) viewModel.setSort(shellSortChoice)
    }
    LaunchedEffect(sort, openCategoryId) {
        onShellSortStateChanged(
            if (openCategoryId != null && sort == CatalogSort.MostVisited)
                CatalogSort.AlphabeticalAsc else sort
        )
    }
    LaunchedEffect(shellRefreshRevision) {
        if (shellRefreshRevision > 0) viewModel.refreshType()
    }

    LaunchedEffect(accountId, contentType, state.categories.size, state.catalogSyncState, landingReload) {
        pinned = repository.landingPinned(contentType)
        metrics = repository.landingMetrics(contentType)
        pinnedLoaded = true
    }
    LaunchedEffect(panelOpen, pickerMode) {
        if (panelOpen) {
            androidx.compose.runtime.withFrameNanos {}
            androidx.compose.runtime.withFrameNanos {}
            runCatching { railFocus.requestFocus() }
        }
    }
    LaunchedEffect(state.refreshConfirmation) {
        if (state.refreshConfirmation != null) {
            showRefreshFeedback = true
            delay(2000)
            showRefreshFeedback = false
            viewModel.dismissRefreshConfirmation()
        }
    }
    BackHandler(enabled = openCategoryId == null && panelOpen) {
        panelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Back)
        scope.launch {
            androidx.compose.runtime.withFrameNanos {}
            runCatching { categoriesFocus.requestFocus() }
        }
    }

    fun open(categoryId: String, filter: String = "") {
        panelSelectedId = categoryId
        openItemFilter = filter.takeIf { it.isNotBlank() }
        onOpenedCategoryChanged(categoryId)
        panelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Select)
    }

    if (openCategoryId != null) {
        val openedSort = if (sort == CatalogSort.MostVisited) CatalogSort.AlphabeticalAsc else sort
        BrowseScreen(
            contentType = contentType, accountId = accountId,
            initialCategoryId = openCategoryId,
            initialItemFilter = openItemFilter,
            landingMode = true, sort = openedSort, onSortChanged = viewModel::setSort,
            showSort = !television,
            categorySort = categorySort, onCategorySortChanged = viewModel::setCategorySort,
            categoryMetrics = metrics,
            onBackToLanding = {
                onOpenedCategoryChanged(null)
                openItemFilter = null
                landingReload++
                restoreSeeAll = lastFocusWasSeeAll && !television
                restoreLandingCard = television
                onReturnToLanding()
            },
            onOpenDetail = onOpenDetailFromGrid,
            onPlay = { item, resumeMs ->
                onPendingFocusConsumed()
                onPlayFromGrid(item, resumeMs)
            }
        )
        return
    }

    val ordered = CatalogLandingPolicy.sorted(state.categories.map { category ->
        val m = metrics[category.categoryId]
        CatalogCategoryOrder(category.categoryId, category.name, category.ordering,
            m?.visits ?: 0L, m?.lastVisitedAt ?: 0, m?.latestAdded ?: 0,
            normalizedName = NameNormalizer.of(category.name).normalized)
    }, categorySort)
    val visible = ordered.filter { category ->
        (state.counts[category.id]?.let { it > 0 } ?: true) &&
            CatalogLandingPolicy.searchMode(category.title, query,
                matchingCounts[category.id] ?: 0) != CatalogSearchMode.Hidden
    }
    val all = state.virtualCategories.firstOrNull { it.categoryId == VirtualFolder.ALL.id }
    val showAll = all != null && (state.counts[VirtualFolder.ALL.id] ?: 0) > 0 &&
        (query.isBlank() || all.name.contains(query.trim(), ignoreCase = true) ||
            matchingCounts.isNotEmpty())
    val pinnedVisible = pinned
    val pinnedRows = if (pinnedLoaded) pinnedVisible.filter { it.cards.isNotEmpty() } else if (hasCachedCatalog) emptyList()
        else landingSpec.pinned.map { spec ->
        HomeShelf(spec.id, spec.title, contentType, emptyList(), core = true,
            emptyHint = spec.emptyHintKey, destination = spec.destination,
            emptyRule = spec.emptyRule)
    }
    val pinnedLazyKeys = CatalogLazyKeys.pinnedRows(pinnedRows.map { it.id })
    val visibleLazyKeys = CatalogLazyKeys.categoryRows(visible.map { it.id })
    val rowIds = pinnedRows.map { it.id } +
        listOfNotNull(all?.categoryId?.takeIf { showAll }) + visible.map { it.id }
    val allRowKey = all?.categoryId?.takeIf { showAll }?.let(CatalogLazyKeys::allFolder)
    val rowKeys = pinnedLazyKeys + listOfNotNull(allRowKey) + visibleLazyKeys
    val nonEmptyRowKeys = pinnedLazyKeys.zip(pinnedRows).filter { (_, shelf) ->
        shelf.cards.isNotEmpty()
    }.map { it.first } +
        listOfNotNull(allRowKey?.takeIf {
            showAll && (state.counts[VirtualFolder.ALL.id] ?: 0) > 0
        }) + visibleLazyKeys.zip(visible).filter { (_, category) ->
            (state.counts[category.id] ?: 0) > 0
        }.map { it.first }
    val rowIndexes = rowKeys.mapIndexed { index, key -> key to index + 1 }.toMap()
    val firstShelfKey = nonEmptyRowKeys.firstOrNull()
    val requesters = remember(rowKeys, nonEmptyRowKeys, shellFocus) {
        rowKeys.map { key ->
            key to if (key == firstShelfKey && shellFocus != null) shellFocus.firstContent else FocusRequester()
        }.toMap()
    }
    val seeAllRequesters = remember(rowKeys) { rowKeys.associateWith { FocusRequester() } }
    val pendingRestoreShelfId = pendingFocusCardKey?.substringBefore('|')
    val cardRestoreKey = pendingFocusCardKey?.substringAfter('|')
        ?.takeUnless { lastFocusWasSeeAll }
    LaunchedEffect(pendingFocusCardKey, lastFocusedShelfId, rowIds, pinnedLoaded, state.catalogSyncState) {
        if (pendingFocusCardKey == null) return@LaunchedEffect
        if (cardRestoreKey.isNullOrEmpty() || pendingRestoreShelfId == null) {
            onPendingFocusConsumed()
            return@LaunchedEffect
        }
        val index = rowIds.indexOf(pendingRestoreShelfId)
        if (index >= 0) listState.scrollToItem(index + 1)
        else if (pinnedLoaded && state.catalogSyncState != "indexing") onPendingFocusConsumed()
    }
    LaunchedEffect(restoreSeeAll, rowIds) {
        if (!restoreSeeAll) return@LaunchedEffect
        val index = rowIds.indexOf(lastFocusedShelfId)
        val rowIndex = rowIds.indexOf(lastFocusedShelfId)
        if (rowIndex < 0) return@LaunchedEffect
        val rowKey = rowKeys[rowIndex]
        val requester = seeAllRequesters[rowKey] ?: return@LaunchedEffect
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem(index + 1)
        repeat(60) {
            androidx.compose.runtime.withFrameNanos {}
            if (runCatching { requester.requestFocus() }.isSuccess) {
                restoreSeeAll = false
                return@LaunchedEffect
            }
        }
        requesters[rowKey]?.let { row ->
            if (runCatching { row.requestFocus() }.isSuccess) restoreSeeAll = false
        }
    }
    LaunchedEffect(restoreLandingCard, rowIds, pinnedLoaded) {
        if (!restoreLandingCard || !pinnedLoaded) return@LaunchedEffect
        val shelfId = lastFocusedShelfId?.takeIf { it in nonEmptyRowKeys }
            ?: nonEmptyRowKeys.firstOrNull()
        val rowIndex = shelfId?.let(rowIndexes::getValue) ?: return@LaunchedEffect
        listState.scrollToItem(if (rowIndex == 1) 0 else rowIndex)
        repeat(60) {
            androidx.compose.runtime.withFrameNanos {}
            if (shelfFocusRegistry.request(shelfId)) {
                restoreLandingCard = false
                return@LaunchedEffect
            }
        }
        requesters[rowKeys[rowIndex - 1]]?.let { row ->
            if (runCatching { row.requestFocus() }.isSuccess) restoreLandingCard = false
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(AppColors.Bg)) {
      Row(Modifier.fillMaxSize()) {
        if (panelOpen && pickerMode == CategoryPickerMode.FullScreen) {
            CategoryRail(
                categories = categoryPickerModel(state.virtualCategories, ordered.map { order ->
                    state.categories.first { it.categoryId == order.id }
                }, CategoryPickerEntryRoute.Landing).filter {
                    panelFilter.isBlank() || it.name.contains(panelFilter.trim(), ignoreCase = true)
                },
                counts = state.counts, selectedCategoryId = panelSelectedId,
                filter = panelFilter, onFilterChanged = { panelFilter = it },
                onSelect = { if (categoryPickerCanSelect(panelOpen, pickerModeSettled)) open(it.categoryId) },
                gridFocusRequester = contentFocus, railFocusRequester = railFocus,
                listState = categoryListState,
                gridAvailable = true, categorySort = categorySort,
                onCategorySortChanged = viewModel::setCategorySort,
                fullScreen = true,
                confineFocus = true
            )
        } else {
        var edgeDrag by remember { mutableStateOf(false) }
        var dragDistance by remember { mutableStateOf(0f) }
        LandingScaffold(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .focusRequester(contentFocus).focusGroup()
                .pointerInput(panelOpen) {
                    detectHorizontalDragGestures(
                        onDragStart = { edgeDrag = it.x < 40.dp.toPx(); dragDistance = 0f },
                        onHorizontalDrag = { _, amount ->
                            if (edgeDrag && !panelOpen) {
                                dragDistance += amount
                                if (dragDistance > 56.dp.toPx()) panelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Open)
                            }
                        }
                    )
                },
            contentPadding = landingContentPadding()
        ) {
            item(key = CatalogLazyKeys.landingHeader()) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().screenHeadingRequester(screenHeadingRequester)
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            if (native.action == KeyEvent.ACTION_DOWN &&
                                native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                                runCatching { categoriesFocus.requestFocus() }.isSuccess
                            } else false
                        }
                ) {
                    Column {
                        Text(contentTypeLabel, color = AppColors.Ink, style = TvType.headline)
                        if (state.catalogSyncState == "indexing" || state.isRefreshing) {
                            Text(stringResource(R.string.browse_refreshing_library, contentTypeLabel), color = AppColors.Dim,
                                style = TvType.label)
                        }
                        state.refreshConfirmation?.takeIf { showRefreshFeedback }?.let {
                            Text(it, color = AppColors.Dim, style = TvType.label)
                            if (it.contains("failed", ignoreCase = true))
                                RetryAction(viewModel::refreshType)
                        }
                        state.error?.takeIf { state.refreshConfirmation == null }?.let { error ->
                            Text(stringResource(when (error) {
                                AppError.RateLimited -> R.string.refresh_rate_limited
                                AppError.ServiceUnavailable -> R.string.refresh_service_unavailable
                                else -> R.string.refresh_unreachable
                            }),
                                color = AppColors.Dim, style = TvType.label)
                            RetryAction(viewModel::refreshType)
                        }
                    }
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.categories_button), color = AppColors.Ink, style = TvType.label,
                            modifier = Modifier.heightIn(min = 48.dp).focusRequester(categoriesFocus)
                                .semantics { contentDescription = openCategoriesDescription }
                                .testTag("category-picker-button")
                                .tvFocusFrame().tvClickable {
                                    panelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Open)
                                }.padding(horizontal = 10.dp))
                        if (!television) HeaderIconButton(Icons.Default.Refresh, stringResource(R.string.common_refresh),
                            onClick = viewModel::refreshType,
                            enabled = !state.isRefreshing && state.catalogSyncState != "indexing",
                            modifier = Modifier.focusRequester(landingRefreshFocus).testTag("landing-refresh"))
                    }
                }
            }
            itemsIndexed(pinnedRows, key = { index, _ -> pinnedLazyKeys[index] }) { rowIndex, shelf ->
                val rowKey = pinnedLazyKeys[rowIndex]
                ShelfRow(shelf, requesters.getValue(rowKey),
                    aboveRequester = shellFocus?.topBar.takeIf { rowKey == firstShelfKey },
                    restoreKey = cardRestoreKey.takeIf { shelf.id == pendingRestoreShelfId }
                        ?.let { ShelfRestoreKey.of(shelf.id, it) },
                    onRestoreConsumed = onPendingFocusConsumed,
                screenHeadingRequester = screenHeadingRequester.takeIf { rowKey == firstShelfKey },
                    focusRegistry = shelfFocusRegistry,
                    onVerticalNavigate = { direction ->
                        navigateBetweenShelves(scope, listState, shelfFocusRegistry,
                            nonEmptyRowKeys, rowIndexes, rowKey, direction,
                            landingRefreshFocus,
                            requestHeader = television && direction == ShelfVerticalDirection.Down)
                    },
                    onPageNavigate = { direction ->
                        navigateShelfPage(scope, listState, shelfFocusRegistry,
                            nonEmptyRowKeys, rowIndexes, direction)
                    },
                    seeAllRequester = seeAllRequesters[rowKey],
                    loadState = if (pinnedLoaded || hasCachedCatalog) ShelfLoadState.Loaded else ShelfLoadState.Loading,
                    onSeeAll = {
                        lastFocusedShelfId = shelf.id
                        lastFocusWasSeeAll = true
                        val folder = shelf.destination?.let { VirtualFolder.fromKey(it) } ?: return@ShelfRow
                        viewModel.markShelfVisited(shelf.id)
                        open(folder.id)
                    },
                    onOpen = {
                        lastFocusedShelfId = shelf.id
                        lastFocusWasSeeAll = false
                        viewModel.markShelfVisited(shelf.id)
                        onOpenDetail(it.item, shelf.id)
                    },
                    onContextMenu = {
                        lastFocusedShelfId = shelf.id
                        lastFocusWasSeeAll = false
                        contextMenuCard = it
                    },
                    onLeadingLeft = { runCatching { categoriesFocus.requestFocus() } })
            }
            if (all != null && showAll) {
                item(key = CatalogLazyKeys.allFolder(all.categoryId)) {
                    LandingCategoryRow(repository, contentType, all, query, sort,
                        all.name.contains(query.trim(), ignoreCase = true),
                        state.counts[all.categoryId] ?: 0,
                        hasCachedCatalog,
                        requesters.getValue(allRowKey!!),
                        shellFocus?.topBar.takeIf { allRowKey == firstShelfKey },
                        onSeeAll = {
                            lastFocusedShelfId = all.categoryId
                            lastFocusWasSeeAll = true
                            viewModel.markShelfVisited(all.categoryId)
                            open(all.categoryId,
                                if (all.name.contains(query.trim(), ignoreCase = true)) "" else query)
                        },
                        onOpen = {
                            lastFocusedShelfId = all.categoryId
                            lastFocusWasSeeAll = false
                            viewModel.markShelfVisited(all.categoryId)
                            onOpenDetail(it.item, all.categoryId)
                        },
                        onContextMenu = {
                            lastFocusedShelfId = all.categoryId
                            lastFocusWasSeeAll = false
                            contextMenuCard = it
                        },
                        restoreKey = cardRestoreKey.takeIf { all.categoryId == pendingRestoreShelfId }
                            ?.let { ShelfRestoreKey.of(all.categoryId, it) },
                        onRestoreConsumed = onPendingFocusConsumed,
                        screenHeadingRequester = screenHeadingRequester.takeIf {
                            allRowKey == firstShelfKey
                        },
                        focusRegistry = shelfFocusRegistry,
                        onVerticalNavigate = { direction ->
                            navigateBetweenShelves(scope, listState, shelfFocusRegistry,
                                nonEmptyRowKeys, rowIndexes, allRowKey!!, direction,
                                landingRefreshFocus,
                                requestHeader = television && direction == ShelfVerticalDirection.Down)
                        },
                        seeAllRequester = seeAllRequesters[allRowKey],
                        onLeadingLeft = { runCatching { categoriesFocus.requestFocus() } })
                }
            }
            itemsIndexed(visible, key = { index, _ -> visibleLazyKeys[index] }) { rowIndex, order ->
                val category = state.categories.first { it.categoryId == order.id }
                val rowKey = visibleLazyKeys[rowIndex]
                val mode = CatalogLandingPolicy.searchMode(order.title, query,
                    matchingCounts[order.id] ?: 0)
                LandingCategoryRow(repository, contentType, category, query, sort,
                mode == CatalogSearchMode.FullFolder,
                state.counts[order.id] ?: 0,
                hasCachedCatalog,
                requesters.getValue(rowKey),
                    shellFocus?.topBar.takeIf { rowKey == firstShelfKey },
                    onSeeAll = {
                        lastFocusedShelfId = order.id
                        lastFocusWasSeeAll = true
                        open(order.id, if (mode == CatalogSearchMode.FullFolder) "" else query)
                    },
                    onOpen = {
                        lastFocusedShelfId = order.id
                        lastFocusWasSeeAll = false
                        viewModel.markCategoryOpened(order.id)
                        onOpenDetail(it.item, order.id)
                    },
                    onContextMenu = {
                        lastFocusedShelfId = order.id
                        lastFocusWasSeeAll = false
                        contextMenuCard = it
                    },
                    restoreKey = cardRestoreKey.takeIf { order.id == pendingRestoreShelfId }
                        ?.let { ShelfRestoreKey.of(order.id, it) },
                    onRestoreConsumed = onPendingFocusConsumed,
                    screenHeadingRequester = screenHeadingRequester.takeIf { rowKey == firstShelfKey },
                    focusRegistry = shelfFocusRegistry,
                    onVerticalNavigate = { direction ->
                        navigateBetweenShelves(scope, listState, shelfFocusRegistry,
                            nonEmptyRowKeys, rowIndexes, rowKey, direction,
                            landingRefreshFocus,
                            requestHeader = television && direction == ShelfVerticalDirection.Down)
                    },
                    seeAllRequester = seeAllRequesters[rowKey],
                    onLeadingLeft = { runCatching { categoriesFocus.requestFocus() } })
            }
        }
        }
      }
      CategoryPickerOverlay(
          visible = panelOpen && pickerMode == CategoryPickerMode.Overlay,
          onDismiss = {
              panelOpen = categoryPickerIsOpenAfter(CategoryPickerAction.Back)
              scope.launch {
                  androidx.compose.runtime.withFrameNanos {}
                  runCatching { categoriesFocus.requestFocus() }
              }
          }
      ) { drawerModifier ->
          CategoryRail(
              categories = categoryPickerModel(state.virtualCategories, ordered.map { order ->
                  state.categories.first { it.categoryId == order.id }
              }, CategoryPickerEntryRoute.Landing).filter { panelFilter.isBlank() || it.name.contains(panelFilter.trim(), ignoreCase = true) },
              counts = state.counts, selectedCategoryId = panelSelectedId,
              filter = panelFilter, onFilterChanged = { panelFilter = it },
              onSelect = { if (categoryPickerCanSelect(panelOpen, pickerModeSettled)) open(it.categoryId) },
              gridFocusRequester = contentFocus, railFocusRequester = railFocus,
              listState = categoryListState,
              gridAvailable = false, categorySort = categorySort,
              onCategorySortChanged = viewModel::setCategorySort,
              overlayDrawer = true, confineFocus = true, modifier = drawerModifier
          )
      }
    }
    contextMenuCard?.let { card ->
        val isFavourite by (viewModel.isFavourite(card.item.id) ?: flowOf(false))
            .collectAsStateWithLifecycle(initialValue = false)
        var hasResume by remember(card.key) { mutableStateOf(false) }
        LaunchedEffect(card.key) {
            hasResume = (viewModel.resumePositionMs(card.item.id) ?: 0L) > 0L
        }
        ItemContextMenu(card.item, isFavourite, hasResume,
            onDismiss = { contextMenuCard = null },
            onPlay = {
                contextMenuCard = null
                lastFocusedShelfId?.let(viewModel::markCategoryOpened)
                scope.launch {
                    val position = viewModel.resumePositionMs(card.item.id) ?: 0L
                    if (position > 0L) resumePromptCard = card to position
                    else onPlay(card.item, 0L, lastFocusedShelfId.orEmpty())
                }
            },
            onToggleFavourite = {
                viewModel.toggleFavourite(card.item.id, !isFavourite)
                contextMenuCard = null
            },
            onClearResume = {
                viewModel.clearResume(card.item.id)
                contextMenuCard = null
            })
    }
    resumePromptCard?.let { (card, position) ->
        ResumePrompt(card.item.title, position,
            onResume = { resumePromptCard = null; onPlay(card.item, position, lastFocusedShelfId.orEmpty()) },
            onStartOver = { resumePromptCard = null; onPlay(card.item, 0L, lastFocusedShelfId.orEmpty()) },
            onDismiss = { resumePromptCard = null })
    }
}

@Composable
private fun RetryAction(onClick: () -> Unit) {
    Text(stringResource(R.string.browse_retry), color = AppColors.AccentText, style = TvType.label,
        modifier = Modifier.heightIn(min = 48.dp).tvFocusFrame().tvClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp))
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LandingCategoryRow(
    repository: HomeRepository, type: ContentType, category: CategoryEntity,
    query: String, sort: CatalogSort, titleMatches: Boolean, revision: Int,
    cachedCatalogPresent: Boolean,
    requester: FocusRequester, aboveRequester: FocusRequester?,
    onSeeAll: () -> Unit, onOpen: (HomeCard) -> Unit,
    onContextMenu: (HomeCard) -> Unit,
    restoreKey: String?, seeAllRequester: FocusRequester?, onLeadingLeft: () -> Unit,
    onRestoreConsumed: () -> Unit,
    screenHeadingRequester: BringIntoViewRequester?,
    focusRegistry: ShelfFocusRegistry,
    onVerticalNavigate: (ShelfVerticalDirection) -> Boolean
) {
    var shelf by remember(category.categoryId, query, titleMatches) { mutableStateOf<HomeShelf?>(null) }
    LaunchedEffect(repository, type, category.categoryId, query, sort, titleMatches, revision) {
        shelf = repository.landingPreview(type, category.categoryId, query, titleMatches, sort)
            .copy(title = category.name, totalCount = revision)
    }
    if (shelf == null && !cachedCatalogPresent && revision <= 0) {
        LandingLoadingSkeleton(channel = type == ContentType.LIVE)
    } else if (shelf != null) {
        ShelfRow(shelf!!, requester, aboveRequester = aboveRequester, restoreKey = restoreKey,
            onRestoreConsumed = onRestoreConsumed,
            screenHeadingRequester = screenHeadingRequester,
            focusRegistry = focusRegistry,
            onVerticalNavigate = onVerticalNavigate,
            onSeeAll = onSeeAll,
            onOpen = onOpen, onContextMenu = onContextMenu, onLeadingLeft = onLeadingLeft,
            seeAllRequester = seeAllRequester)
    }
}

private fun ContentType.toShelfContentType() = when (this) {
    ContentType.MOVIES -> ShelfContentType.Movie
    ContentType.SERIES -> ShelfContentType.Series
    ContentType.LIVE -> ShelfContentType.Live
}
