package com.dev.Tvivo.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.AppErrorException
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.dao.CategoryCount
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.repository.LiveRepository
import com.dev.Tvivo.data.repository.PlaybackStateRepository
import com.dev.Tvivo.data.repository.SeriesRepository
import com.dev.Tvivo.data.repository.VodRepository
import com.dev.Tvivo.sync.CatalogSyncer
import com.dev.Tvivo.ui.home.ContentType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val categories: List<CategoryEntity> = emptyList(),
    val counts: Map<String?, Int> = emptyMap(),
    val selectedCategoryId: String? = null,
    val isLoadingCategories: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: AppError? = null,
    /** Shown for 2 s after a manual refresh: a refresh that changes nothing is
     *  visually identical to a broken button. */
    val refreshConfirmation: String? = null,
    /** Full-catalog progress. Dismisses at 100%; gates ALL, RECENTLY ADDED and search. */
    val catalogSyncState: String? = null,
    val catalogSyncDone: Int = 0,

    /** D-13. Filters the **rail**, in memory — the category list is ~120 rows. */
    val categoryFilter: String = "",

    /** D-14. Filters the **grid**, in SQL, scoped to the selected category. */
    val itemFilter: String = "",
    val isItemFilterOpen: Boolean = false,
    /** The `N` of `N of M`; null while unfiltered, when `M` alone is the honest number. */
    val filteredCount: Int? = null,

    /** The three folders the panel does not publish, already ordered. */
    val virtualCategories: List<CategoryEntity> = emptyList()
) {
    /**
     * D-13. Rail filtering is a plain substring match over what is already in memory, so
     * it needs no query, no debounce and no DAO — the panel publishes ~120 categories and
     * they are all resident.
     *
     * Matching is on the **raw** name the rail draws, not on a normalized column, because
     * this list is what the user is looking at while they type. `RAMADAN EGYPT 2026 SD`
     * and `... HD` must both survive a search for `ramadan`, which is precisely why the
     * rail never truncates them (Q-12).
     */
    val visibleCategories: List<CategoryEntity>
        get() {
            // Virtual folders lead. They are the three shortest routes to something the
            // user already cares about, and a rail that opens on `ARABIC MOVIES 2026`
            // buries them under ~120 rows of panel naming.
            val all = virtualCategories + categories
            if (categoryFilter.isBlank()) return all
            val needle = categoryFilter.trim().lowercase()
            // Filtered like any other row: `fav` should find FAVOURITES. Exempting them
            // would make the filter lie about what the rail contains.
            return all.filter { it.name.lowercase().contains(needle) }
        }
}

/**
 * One browse ViewModel for every content type. [contentType] picks the repository and
 * the card shape; nothing else in the screen knows which type it is showing.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BrowseViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
    val contentType: ContentType
) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val store = CredentialsStore(application)

    /** The Room `contentType` key: `vod` / `live` / `series`, shared with resume and
     *  favourites. */
    private val typeKey = when (contentType) {
        ContentType.LIVE -> TYPE_LIVE
        ContentType.SERIES -> TYPE_SERIES
        else -> TYPE_VOD
    }

    val cardShape = when (contentType) {
        ContentType.LIVE -> CardShape.CHANNEL
        else -> CardShape.POSTER
    }

    /**
     * Live has no meaningful resume point, and a *show* is not a playable thing — resume
     * belongs to its episodes, which the detail screen owns. Both skip the lookup, so
     * neither ever raises a resume prompt over an item that cannot honour it.
     */
    private val tracksResume = contentType == ContentType.MOVIES

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    private val selectedCategory = MutableStateFlow<String?>(null)

    /**
     * D-14. Debounced before it reaches Room: the filter is a `LIKE '%x%'` scan of the
     * category, so every keystroke would otherwise be a fresh scan plus a fresh
     * `PagingSource`. 300 ms is long enough to swallow a burst of D-pad keystrokes and
     * short enough not to feel laggy.
     *
     * The *typed* value lives in `BrowseUiState` so the field stays responsive; this is
     * only what the query is allowed to see.
     */
    private val debouncedItemFilter = MutableStateFlow("")

    /**
     * Focus restoration is by stable item ID, held in `SavedStateHandle` rather than a
     * ViewModel field: the browse Activity gets killed under memory pressure while a
     * 1080p stream decodes on a 1–2 GB box.
     */
    var pendingFocusItemId: Int?
        get() = savedState["pendingFocusItemId"]
        set(value) { savedState["pendingFocusItemId"] = value }

    private var source: CatalogSource? = null
    private var playbackState: PlaybackStateRepository? = null
    private var catalogSyncer: CatalogSyncer? = null

    var accountId: String? = null
        private set

    init {
        viewModelScope.launch {
            val credentials = store.load()
            if (credentials == null) {
                DiagnosticLog.error("browse", "No stored credentials; cannot load $contentType")
                _state.update { it.copy(isLoadingCategories = false, error = AppError.AuthFailed) }
                return@launch
            }
            val id = AccountIdentity.of(credentials)
            accountId = id
            val catalog = when (contentType) {
                ContentType.LIVE -> CatalogSource.live(LiveRepository(db, credentials, id))
                ContentType.SERIES -> CatalogSource.series(SeriesRepository(db, credentials, id))
                else -> CatalogSource.vod(VodRepository(db, credentials, id))
            }
            source = catalog
            val playback = PlaybackStateRepository(db, id)
            playbackState = playback
            val syncer = CatalogSyncer(db, credentials, id)
            catalogSyncer = syncer

            viewModelScope.launch {
                syncer.observeProgress(typeKey).collect { progress ->
                    _state.update {
                        it.copy(
                            catalogSyncState = progress?.state,
                            catalogSyncDone = progress?.done ?: 0
                        )
                    }
                }
            }

            val virtuals = VirtualFolder.forContentType(contentType == ContentType.LIVE)
                .mapIndexed { index, folder -> folder.toCategory(id, typeKey, -100 + index) }
            _state.update { it.copy(virtualCategories = virtuals) }

            viewModelScope.launch {
                catalog.observeCategories().collect { categories ->
                    _state.update {
                        it.copy(
                            categories = categories,
                            isLoadingCategories = false,
                            selectedCategoryId = it.selectedCategoryId
                                ?: virtuals.firstOrNull()?.categoryId
                                ?: categories.firstOrNull()?.categoryId
                        )
                    }
                    // The rail opens on the first row, and the first row is now a
                    // virtual folder — so that is what the grid must be showing, or the
                    // highlight and the content disagree on entry.
                    if (selectedCategory.value == null) {
                        val first = virtuals.firstOrNull()?.categoryId
                            ?: categories.firstOrNull()?.categoryId
                        first?.let { selectCategory(it) }
                    }
                }
            }

            // D-14 — the `N` of `N of M`. Counted in SQL rather than taken from the
            // paging list, whose size is not known until every page has been loaded.
            viewModelScope.launch {
                combine(
                    selectedCategory,
                    debouncedItemFilter.debounce(FILTER_DEBOUNCE_MS)
                ) { id, q -> id to q }
                    .flatMapLatest { (categoryId, query) ->
                        when {
                            categoryId == null || query.isBlank() -> flowOf(null)
                            // Counted off the in-memory list in `virtualItems`, not in
                            // SQL: there is no table to count.
                            VirtualFolder.isVirtual(categoryId) -> flowOf(null)
                            else -> catalog.countFiltered(categoryId, query)
                        }
                    }
                    .collect { count -> _state.update { it.copy(filteredCount = count) } }
            }

            viewModelScope.launch {
                catalog.countsByCategory().collect { counts ->
                    _state.update { s ->
                        s.copy(counts = s.counts + counts.associate { it.categoryId to it.count })
                    }
                }
            }

            // The virtual folders' own counts. Live-updating for the same reason the
            // panel categories' are: a folder that says nothing until you open it is a
            // folder you have to open to find out it is empty.
            playback?.let { pb ->
                if (contentType != ContentType.LIVE) {
                    viewModelScope.launch {
                        pb.continueWatching(typeKey).collect { rows ->
                            putCount(VirtualFolder.CONTINUE_WATCHING.id, rows.size)
                        }
                    }
                }
                viewModelScope.launch {
                    pb.favourites(typeKey).collect { rows ->
                        putCount(VirtualFolder.FAVOURITES.id, rows.size)
                    }
                }
            }
            viewModelScope.launch {
                catalog.recentlyAdded(VirtualFolder.RECENTLY_ADDED_LIMIT).collect { rows ->
                    putCount(VirtualFolder.RECENTLY_ADDED.id, rows.size)
                }
            }

            catalog.refreshCategories(false)
                .onFailure { t -> _state.update { it.copy(error = t.toAppError()) } }

            // Behind the categories, never in front of them: the grid is usable while
            // this runs, and it must not be what the user waits on.
            viewModelScope.launch {
                when (contentType) {
                    ContentType.LIVE -> syncer.syncLive()
                    ContentType.SERIES -> syncer.syncSeries()
                    else -> syncer.syncVod()
                }
            }
        }
    }

    val items: Flow<PagingData<BrowseItem>> =
        combine(selectedCategory, debouncedItemFilter.debounce(FILTER_DEBOUNCE_MS)) { id, q ->
            id to q
        }
            .flatMapLatest { (categoryId, query) ->
                val catalog = source
                when {
                    catalog == null || categoryId == null -> flowOf(PagingData.empty())
                    // Virtual folders are bounded — 50, 100, and however many the user
                    // has favourited — so they are built as one list rather than paged.
                    // Paging exists for the 48,761-row case and buys nothing here.
                    VirtualFolder.isVirtual(categoryId) -> virtualItems(catalog, categoryId, query)
                    query.isBlank() -> catalog.pagingInCategory(categoryId)
                    else -> catalog.pagingFiltered(categoryId, query)
                }
            }
            .cachedIn(viewModelScope)

    private fun putCount(categoryId: String, count: Int) =
        _state.update { it.copy(counts = it.counts + (categoryId to count)) }

    /**
     * A virtual folder's rows, as a single page.
     *
     * The item filter still applies — a folder is a list like any other, and 100 rows is
     * exactly the size where wanting to narrow it is reasonable. Matched against the
     * title the grid actually draws, because that is what the user is reading.
     */
    private fun virtualItems(
        catalog: CatalogSource,
        categoryId: String,
        query: String
    ): Flow<PagingData<BrowseItem>> {
        val playback = playbackState
        val rows: Flow<List<BrowseItem>> = when (VirtualFolder.of(categoryId)) {
            VirtualFolder.RECENTLY_ADDED ->
                catalog.recentlyAdded(VirtualFolder.RECENTLY_ADDED_LIMIT)

            VirtualFolder.CONTINUE_WATCHING ->
                playback?.continueWatching(typeKey)
                    ?.map { rows -> catalog.byIds(rows.mapNotNull { it.itemId.toIntOrNull() }) }
                    ?: flowOf(emptyList())

            VirtualFolder.FAVOURITES ->
                playback?.favourites(typeKey)
                    ?.map { rows -> catalog.byIds(rows.mapNotNull { it.itemId.toIntOrNull() }) }
                    ?: flowOf(emptyList())

            null -> flowOf(emptyList())
        }
        return rows.map { list ->
            val needle = query.trim().lowercase()
            val filtered =
                if (needle.isBlank()) list
                else list.filter { it.title.lowercase().contains(needle) }
            // **The load states are not optional here.** `PagingData.from(list)` without
            // them leaves `refresh` as `Loading` forever, so the grid sits on its
            // spinner and reports `itemCount == 0` over a list it is already holding —
            // a folder with 100 rows in it rendering as "Loading…". `endOfPagination`
            // is true because this *is* the whole list; there is no next page.
            PagingData.from(
                filtered,
                sourceLoadStates = LoadStates(
                    refresh = LoadState.NotLoading(endOfPaginationReached = true),
                    prepend = LoadState.NotLoading(endOfPaginationReached = true),
                    append = LoadState.NotLoading(endOfPaginationReached = true)
                )
            )
        }
    }

    /** D-13. In-memory only: no query, no refresh, nothing hits the panel. */
    fun onCategoryFilterChanged(text: String) {
        _state.update { it.copy(categoryFilter = text) }
    }

    /**
     * D-14. Opening reveals the field; closing **clears the filter as well as hiding it**.
     * A hidden filter that is still applied is a grid silently missing rows, with no
     * control on screen to explain why.
     */
    fun setItemFilterOpen(open: Boolean) {
        if (open) {
            _state.update { it.copy(isItemFilterOpen = true) }
        } else {
            debouncedItemFilter.value = ""
            _state.update { it.copy(isItemFilterOpen = false, itemFilter = "", filteredCount = null) }
        }
    }

    fun onItemFilterChanged(text: String) {
        _state.update { it.copy(itemFilter = text) }
        debouncedItemFilter.value = text
    }

    fun selectCategory(categoryId: String) {
        selectedCategory.value = categoryId
        // Carrying a filter across categories would open the new one already filtered, by
        // text the user typed for a different list.
        debouncedItemFilter.value = ""
        _state.update { it.copy(itemFilter = "", filteredCount = null) }
        _state.update { it.copy(selectedCategoryId = categoryId, error = null) }
        // A virtual folder has no panel category behind it: `__continue` is not an id
        // the panel would recognise, and asking it to refresh one is a guaranteed error
        // toast over a folder that is already showing the right rows.
        if (VirtualFolder.isVirtual(categoryId)) return
        viewModelScope.launch {
            source?.refreshCategory(categoryId, false)
                ?.onFailure { t -> _state.update { it.copy(error = t.toAppError()) } }
        }
    }

    /** Manual refresh ignores the TTL, always fetches, same transaction. */
    fun refreshSelected() {
        val categoryId = selectedCategory.value ?: return
        // Same reason as `selectCategory`. The folders are views over local data and are
        // already live — there is nothing to go and fetch.
        if (VirtualFolder.isVirtual(categoryId)) {
            _state.update { it.copy(refreshConfirmation = "Up to date") }
            return
        }
        // A refresh replaces the selected category's rows. Keeping its old card id or a
        // filtered rail selection during that replacement leaves focus pointed at data
        // which no longer exists. Move immediately to the same safe entry used on first
        // load, while retaining categoryId locally for the in-flight request.
        resetToDefaultSelection()
        _state.update { it.copy(isRefreshing = true, error = null, refreshConfirmation = null) }
        viewModelScope.launch {
            source?.refreshCategory(categoryId, true)
                ?.onSuccess {
                    _state.update {
                        it.copy(isRefreshing = false, refreshConfirmation = "Up to date")
                    }
                }
                ?.onFailure { t ->
                    _state.update { it.copy(isRefreshing = false, error = t.toAppError()) }
                }
        }
    }

    fun dismissRefreshConfirmation() = _state.update { it.copy(refreshConfirmation = null) }

    private fun resetToDefaultSelection() {
        val defaultId = _state.value.virtualCategories.firstOrNull()?.categoryId
            ?: _state.value.categories.firstOrNull()?.categoryId
            ?: return
        pendingFocusItemId = null
        selectedCategory.value = defaultId
        debouncedItemFilter.value = ""
        _state.update {
            it.copy(
                selectedCategoryId = defaultId,
                categoryFilter = "",
                itemFilter = "",
                isItemFilterOpen = false,
                filteredCount = null
            )
        }
        DiagnosticLog.info("Browse", "selection reset for refresh", "contentType=$contentType")
    }

    /** Resume prompt data: null when there is nothing to resume from. */
    suspend fun resumePositionMs(itemId: Int): Long? =
        if (!tracksResume) null
        else playbackState?.resumePosition(typeKey, itemId.toString())?.positionMs

    fun isFavourite(itemId: Int): Flow<Boolean>? =
        playbackState?.isFavourite(typeKey, itemId.toString())

    fun toggleFavourite(itemId: Int, makeFavourite: Boolean) {
        viewModelScope.launch {
            playbackState?.toggleFavourite(typeKey, itemId.toString(), makeFavourite)
        }
    }

    fun clearResume(itemId: Int) {
        viewModelScope.launch { playbackState?.clearResume(typeKey, itemId.toString()) }
    }

    /**
     * Q-22 — every browse failure lands here, so this is the one place that has to
     * record it. The content type and the mapped [AppError], never the category name or
     * the item: the log is operational, not a record of what was watched.
     */
    private fun Throwable.toAppError(): AppError {
        val mapped = (this as? AppErrorException)?.error ?: AppError.Unreachable
        DiagnosticLog.error("browse", "$contentType: $mapped")
        return mapped
    }

    private companion object {
        /** Long enough to swallow a D-pad keystroke burst, short enough not to feel laggy. */
        const val FILTER_DEBOUNCE_MS = 300L
    }
}

/**
 * The repository surface the screen actually needs, with the entity type erased at the
 * boundary. Adding series in Phase 4 means adding one factory here, not another
 * ViewModel.
 */
private class CatalogSource(
    val observeCategories: () -> Flow<List<CategoryEntity>>,
    val countsByCategory: () -> Flow<List<CategoryCount>>,
    val refreshCategories: suspend (Boolean) -> Result<Boolean>,
    val refreshCategory: suspend (String, Boolean) -> Result<Boolean>,
    val pagingInCategory: (String) -> Flow<PagingData<BrowseItem>>,
    val pagingFiltered: (String, String) -> Flow<PagingData<BrowseItem>>,
    val countFiltered: (String, String) -> Flow<Int>,
    /** `RECENTLY ADDED`, newest first, catalog-wide. */
    val recentlyAdded: (Int) -> Flow<List<BrowseItem>>,
    /** Resolves Continue watching / Favourites ids into rows, in the ids' own order. */
    val byIds: suspend (List<Int>) -> List<BrowseItem>
) {
    companion object {
        /**
         * Not a tuning knob: with placeholders off, an item at index 800 does not exist
         * in the list until the user scrolls there, so focus restoration cannot find it
         * and silently degrades to "top of grid" — unfixable in UI code afterwards.
         */
        private val config = PagingConfig(
            pageSize = 60,
            enablePlaceholders = true,
            prefetchDistance = 10
        )

        fun vod(repo: VodRepository) = CatalogSource(
            observeCategories = repo::observeCategories,
            countsByCategory = repo::countsByCategory,
            refreshCategories = { force -> repo.refreshCategories(force) },
            refreshCategory = { id, force -> repo.refreshCategory(id, force) },
            pagingInCategory = { id ->
                Pager(config) { repo.pagingInCategory(id) }.flow
                    .map { data -> data.map { it.toBrowseItem() } }
            },
            pagingFiltered = { id, query ->
                Pager(config) { repo.pagingInCategoryFiltered(id, query) }.flow
                    .map { data -> data.map { it.toBrowseItem() } }
            },
            countFiltered = { id, query -> repo.countInCategoryFiltered(id, query) },
            recentlyAdded = { limit ->
                repo.recentlyAdded(limit).map { rows -> rows.map { it.toBrowseItem() } }
            },
            byIds = { ids ->
                // Re-ordered to the ids' own order, which is the order carrying the
                // meaning — last watched first, most recently favourited first. SQL
                // `IN` returns rows in whatever order suits it.
                val rows = repo.byIds(ids).map { it.toBrowseItem() }.associateBy { it.id }
                ids.mapNotNull { rows[it] }
            }
        )

        fun series(repo: SeriesRepository) = CatalogSource(
            observeCategories = repo::observeCategories,
            countsByCategory = repo::countsByCategory,
            refreshCategories = { force -> repo.refreshCategories(force) },
            refreshCategory = { id, force -> repo.refreshCategory(id, force) },
            pagingInCategory = { id ->
                Pager(config) { repo.pagingInCategory(id) }.flow
                    .map { data -> data.map { it.toBrowseItem() } }
            },
            pagingFiltered = { id, query ->
                Pager(config) { repo.pagingInCategoryFiltered(id, query) }.flow
                    .map { data -> data.map { it.toBrowseItem() } }
            },
            countFiltered = { id, query -> repo.countInCategoryFiltered(id, query) },
            recentlyAdded = { limit ->
                repo.recentlyAdded(limit).map { rows -> rows.map { it.toBrowseItem() } }
            },
            byIds = { ids ->
                // Re-ordered to the ids' own order, which is the order carrying the
                // meaning — last watched first, most recently favourited first. SQL
                // `IN` returns rows in whatever order suits it.
                val rows = repo.byIds(ids).map { it.toBrowseItem() }.associateBy { it.id }
                ids.mapNotNull { rows[it] }
            }
        )

        fun live(repo: LiveRepository) = CatalogSource(
            observeCategories = repo::observeCategories,
            countsByCategory = repo::countsByCategory,
            refreshCategories = { force -> repo.refreshCategories(force) },
            refreshCategory = { id, force -> repo.refreshCategory(id, force) },
            pagingInCategory = { id ->
                Pager(config) { repo.pagingInCategory(id) }.flow
                    .map { data -> data.map { it.toBrowseItem() } }
            },
            pagingFiltered = { id, query ->
                Pager(config) { repo.pagingInCategoryFiltered(id, query) }.flow
                    .map { data -> data.map { it.toBrowseItem() } }
            },
            countFiltered = { id, query -> repo.countInCategoryFiltered(id, query) },
            recentlyAdded = { limit ->
                repo.recentlyAdded(limit).map { rows -> rows.map { it.toBrowseItem() } }
            },
            byIds = { ids ->
                // Re-ordered to the ids' own order, which is the order carrying the
                // meaning — last watched first, most recently favourited first. SQL
                // `IN` returns rows in whatever order suits it.
                val rows = repo.byIds(ids).map { it.toBrowseItem() }.associateBy { it.id }
                ids.mapNotNull { rows[it] }
            }
        )
    }
}
