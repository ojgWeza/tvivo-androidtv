package com.dev.Tvivo.ui.series

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.AppErrorException
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.repository.PlaybackStateRepository
import com.dev.Tvivo.data.repository.SeriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SeasonTab(val number: Int, val label: String, val episodeCount: Int)

data class SeriesDetailUiState(
    val title: String = "",
    val plot: String? = null,
    val coverUrl: String? = null,
    val seasons: List<SeasonTab> = emptyList(),
    val selectedSeason: Int? = null,
    val episodes: List<EpisodeEntity> = emptyList(),
    /** Only while there is nothing cached to show — a refresh over cached episodes is a
     *  line under the header, never a blocking spinner. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: AppError? = null
)

/**
 * The layer series has and the other two content types do not: one show, its seasons,
 * and the episodes inside them.
 *
 * `get_series_info` is fetched lazily per show and cached on its own TTL, so re-opening a
 * show inside 24 h is a Room read. Cached episodes always render, error or not — a failed
 * refresh must not take a show the user could otherwise still play offline.
 */
class SeriesDetailViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
    private val seriesId: Int
) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val store = CredentialsStore(application)

    private val _state = MutableStateFlow(SeriesDetailUiState())
    val state: StateFlow<SeriesDetailUiState> = _state.asStateFlow()

    private var repo: SeriesRepository? = null
    private var playbackState: PlaybackStateRepository? = null

    /** All episodes of the show, ungrouped, as cached. Grouped into seasons on emit. */
    private var allEpisodes: List<EpisodeEntity> = emptyList()

    /** Survives process death for the same reason grid focus does — the Activity can be
     *  killed while a 1080p stream decodes on a 1–2 GB box. */
    var pendingFocusEpisodeId: String?
        get() = savedState["pendingFocusEpisodeId"]
        set(value) { savedState["pendingFocusEpisodeId"] = value }

    init {
        viewModelScope.launch {
            val credentials = store.load()
            if (credentials == null) {
                _state.update { it.copy(isLoading = false, error = AppError.AuthFailed) }
                return@launch
            }
            val accountId = AccountIdentity.of(credentials)
            val repository = SeriesRepository(db, credentials, accountId)
            repo = repository
            playbackState = PlaybackStateRepository(db, accountId)

            repository.byId(seriesId)?.let { show ->
                _state.update {
                    it.copy(title = show.nameDisplay, plot = show.plot, coverUrl = show.streamIcon)
                }
            }

            viewModelScope.launch {
                repository.observeEpisodes(seriesId).collect { episodes ->
                    allEpisodes = episodes
                    emitSeasons(repository.lastSeasonNames)
                }
            }

            repository.refreshSeriesInfo(seriesId, force = false)
                .onFailure { t -> _state.update { it.copy(error = t.toAppError()) } }
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun selectSeason(number: Int) {
        _state.update { it.copy(selectedSeason = number) }
        emitSeasons(repo?.lastSeasonNames.orEmpty())
    }

    fun refresh() {
        val repository = repo ?: return
        _state.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            repository.refreshSeriesInfo(seriesId, force = true)
                .onFailure { t -> _state.update { it.copy(error = t.toAppError()) } }
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    suspend fun resumePositionMs(episodeId: String): Long? =
        playbackState?.resumePosition(TYPE_SERIES, episodeId)?.positionMs

    fun clearResume(episodeId: String) {
        viewModelScope.launch { playbackState?.clearResume(TYPE_SERIES, episodeId) }
    }

    /** Favourites are kept at *show* level: a show is what a viewer follows. */
    fun isFavourite(): Flow<Boolean>? =
        playbackState?.isFavourite(TYPE_SERIES, seriesId.toString())

    fun toggleFavourite(makeFavourite: Boolean) {
        viewModelScope.launch {
            playbackState?.toggleFavourite(TYPE_SERIES, seriesId.toString(), makeFavourite)
        }
    }

    private fun emitSeasons(seasonNames: Map<Int, String>) {
        val grouped = allEpisodes.groupBy { it.seasonNumber }
        val seasons = grouped.keys.sorted().map { number ->
            SeasonTab(
                number = number,
                // Season 0 is specials on every panel that uses it, and "Season 0" reads
                // as a bug to a viewer.
                label = seasonNames[number] ?: if (number == 0) "Specials" else "Season $number",
                episodeCount = grouped[number]?.size ?: 0
            )
        }
        _state.update { current ->
            val selected = current.selectedSeason?.takeIf { grouped.containsKey(it) }
                ?: seasons.firstOrNull()?.number
            current.copy(
                seasons = seasons,
                selectedSeason = selected,
                episodes = grouped[selected].orEmpty()
            )
        }
    }

    private fun Throwable.toAppError(): AppError =
        (this as? AppErrorException)?.error ?: AppError.Unreachable
}
