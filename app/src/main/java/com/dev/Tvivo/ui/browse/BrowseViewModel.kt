package com.dev.Tvivo.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.AppErrorException
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.data.AppError
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
import kotlinx.coroutines.flow.asStateFlow
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
    val catalogSyncDone: Int = 0
)

/**
 * One browse ViewModel for every content type. [contentType] picks the repository and
 * the card shape; nothing else in the screen knows which type it is showing.
 */
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
            playbackState = PlaybackStateRepository(db, id)
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

            viewModelScope.launch {
                catalog.observeCategories().collect { categories ->
                    _state.update {
                        it.copy(
                            categories = categories,
                            isLoadingCategories = false,
                            selectedCategoryId = it.selectedCategoryId
                                ?: categories.firstOrNull()?.categoryId
                        )
                    }
                    if (selectedCategory.value == null) {
                        categories.firstOrNull()?.let { first -> selectCategory(first.categoryId) }
                    }
                }
            }

            viewModelScope.launch {
                catalog.countsByCategory().collect { counts ->
                    _state.update { s ->
                        s.copy(counts = counts.associate { it.categoryId to it.count })
                    }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<BrowseItem>> = selectedCategory
        .flatMapLatest { categoryId ->
            val catalog = source
            if (catalog == null || categoryId == null) {
                flowOf(PagingData.empty())
            } else {
                catalog.pagingInCategory(categoryId)
            }
        }
        .cachedIn(viewModelScope)

    fun selectCategory(categoryId: String) {
        selectedCategory.value = categoryId
        _state.update { it.copy(selectedCategoryId = categoryId, error = null) }
        viewModelScope.launch {
            source?.refreshCategory(categoryId, false)
                ?.onFailure { t -> _state.update { it.copy(error = t.toAppError()) } }
        }
    }

    /** Manual refresh ignores the TTL, always fetches, same transaction. */
    fun refreshSelected() {
        val categoryId = selectedCategory.value ?: return
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

    private fun Throwable.toAppError(): AppError =
        (this as? AppErrorException)?.error ?: AppError.Unreachable
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
    val pagingInCategory: (String) -> Flow<PagingData<BrowseItem>>
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
            }
        )
    }
}
