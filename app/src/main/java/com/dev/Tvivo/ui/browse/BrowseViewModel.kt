package com.dev.Tvivo.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.AppErrorException
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import com.dev.Tvivo.data.repository.VodRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
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
    val refreshConfirmation: String? = null
)

class BrowseViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val store = CredentialsStore(application)

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    private val selectedCategory = MutableStateFlow<String?>(null)

    /**
     * Focus restoration is by stable item ID, held in `SavedStateHandle` rather than a
     * ViewModel field: the browse Activity gets killed under memory pressure while a
     * 1080p stream decodes on a 1–2 GB box.
     */
    var pendingFocusStreamId: Int?
        get() = savedState["pendingFocusStreamId"]
        set(value) { savedState["pendingFocusStreamId"] = value }

    private var repository: VodRepository? = null

    init {
        viewModelScope.launch {
            val credentials = store.load()
            if (credentials == null) {
                _state.update { it.copy(isLoadingCategories = false, error = AppError.AuthFailed) }
                return@launch
            }
            val accountId = AccountIdentity.of(credentials)
            val repo = VodRepository(db, credentials, accountId)
            repository = repo

            viewModelScope.launch {
                repo.observeCategories().collect { categories ->
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
                repo.countsByCategory().collect { counts ->
                    _state.update { s ->
                        s.copy(counts = counts.associate { it.categoryId to it.count })
                    }
                }
            }

            repo.refreshCategories()
                .onFailure { t -> _state.update { it.copy(error = t.toAppError()) } }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<VodStreamEntity>> = selectedCategory
        .flatMapLatest { categoryId ->
            val repo = repository
            if (repo == null || categoryId == null) {
                flowOf(PagingData.empty())
            } else {
                Pager(
                    config = PagingConfig(
                        pageSize = 60,
                        // Not a tuning knob: with placeholders off, an item at index 800
                        // does not exist in the list until the user scrolls there, so
                        // focus restoration cannot find it and silently degrades to
                        // "top of grid" — unfixable in UI code afterwards.
                        enablePlaceholders = true,
                        prefetchDistance = 10
                    ),
                    pagingSourceFactory = { repo.pagingInCategory(categoryId) }
                ).flow
            }
        }
        .cachedIn(viewModelScope)

    fun selectCategory(categoryId: String) {
        selectedCategory.value = categoryId
        _state.update { it.copy(selectedCategoryId = categoryId, error = null) }
        viewModelScope.launch {
            repository?.refreshCategory(categoryId)
                ?.onFailure { t -> _state.update { it.copy(error = t.toAppError()) } }
        }
    }

    /** Manual refresh ignores the TTL, always fetches, same transaction. */
    fun refreshSelected() {
        val categoryId = selectedCategory.value ?: return
        _state.update { it.copy(isRefreshing = true, error = null, refreshConfirmation = null) }
        viewModelScope.launch {
            repository?.refreshCategory(categoryId, force = true)
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

    private fun Throwable.toAppError(): AppError =
        (this as? AppErrorException)?.error ?: AppError.Unreachable
}
