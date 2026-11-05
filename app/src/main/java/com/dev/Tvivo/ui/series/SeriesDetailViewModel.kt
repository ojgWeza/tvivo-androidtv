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
import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.repository.PlaybackStateRepository
import com.dev.Tvivo.data.repository.SeriesRepository
import com.dev.Tvivo.data.repository.MediaFileSizeRepository
import com.dev.Tvivo.data.StreamUrlBuilder
import com.dev.Tvivo.data.remote.HttpFileSizeProbe
import com.dev.Tvivo.diagnostics.NetworkEventKind
import com.dev.Tvivo.diagnostics.NetworkEventRecorder
import com.dev.Tvivo.diagnostics.NetworkEventSource
import com.dev.Tvivo.ui.detail.FileSizeDisplay
import com.dev.Tvivo.ui.detail.FileSizeFormatter
import com.dev.Tvivo.playback.DetailEpisode
import com.dev.Tvivo.playback.DetailPlaybackPolicy
import com.dev.Tvivo.playback.SeriesActions
import com.dev.Tvivo.download.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class SeasonTab(val number: Int, val label: String, val episodeCount: Int)

data class SeriesDetailUiState(
    val title: String = "",
    val plot: String? = null,
    val coverUrl: String? = null,
    val rating: Double? = null,
    val quality: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val isFavourite: Boolean = false,
    val playback: Map<String, DetailEpisode> = emptyMap(),
    val lastFocusedEpisodes: Map<Int, String> = emptyMap(),
    val actions: SeriesActions = SeriesActions(null),
    val seasons: List<SeasonTab> = emptyList(),
    val selectedSeason: Int? = null,
    val episodes: List<EpisodeEntity> = emptyList(),
    val fileSizes: Map<String, FileSizeDisplay> = emptyMap(),
    val downloadStates: Map<String, String> = emptyMap(),
    val downloadWarning: DownloadQuota.Evaluation.ExceedsCap? = null,
    val downloadWarningUsedBytes: Long = 0,
    val downloadWarningItemCount: Int = 0,
    val downloadMessage: String? = null,
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
    private var accountId: String? = null
    private var credentials: com.dev.Tvivo.auth.Credentials? = null
    private var sizeJob: kotlinx.coroutines.Job? = null
    private var sizeProbeJob: kotlinx.coroutines.Job? = null
    private var primaryProbeRequested = false
    private var playRequested = false

    /** All episodes of the show, ungrouped, as cached. Grouped into seasons on emit. */
    private var allEpisodes: List<EpisodeEntity> = emptyList()

    /** Ordered show queue for the player's explicit next-episode action. */
    fun playbackQueue(): List<EpisodeEntity> = allEpisodes.toList()

    /** Survives process death for the same reason grid focus does — the Activity can be
     *  killed while a 1080p stream decodes on a 1–2 GB box. */
    var pendingFocusEpisodeId: String?
        get() = savedState["pendingFocusEpisodeId"]
        set(value) { savedState["pendingFocusEpisodeId"] = value }

    fun focusedEpisodeInSeason(season: Int): String? = savedState["seasonFocus_$season"]
    fun rememberEpisodeFocus(season: Int, episodeId: String) {
        savedState["seasonFocus_$season"] = episodeId
        pendingFocusEpisodeId = episodeId
        _state.update { it.copy(lastFocusedEpisodes = it.lastFocusedEpisodes + (season to episodeId)) }
    }

    init {
        viewModelScope.launch {
            val credentials = store.load()
            if (credentials == null) {
                _state.update { it.copy(isLoading = false, error = AppError.AuthFailed) }
                return@launch
            }
            val accountId = AccountIdentity.of(credentials)
            this@SeriesDetailViewModel.credentials = credentials
            this@SeriesDetailViewModel.accountId = accountId
            viewModelScope.launch {
                DownloadDependencies.repository(application).observe(accountId).collect { rows ->
                    _state.update { it.copy(downloadStates = rows.filter { row -> row.kind == "episode" && row.seriesId == seriesId }
                        .associate { row ->
                            val percent = row.expectedBytes?.takeIf { it > 0 }?.let { (row.partialBytes * 100 / it).toInt().coerceIn(0, 99) }
                            row.mediaId to if (row.state == "DOWNLOADING") "DOWNLOADING:${percent ?: 0}" else row.state
                        }) }
                }
            }
            val repository = SeriesRepository(db, credentials, accountId)
            repo = repository
            playbackState = PlaybackStateRepository(db, accountId)

            repository.byId(seriesId)?.let { show ->
                _state.update {
                    it.copy(title = show.nameDisplay, plot = show.plot, coverUrl = show.streamIcon,
                        rating = show.rating, quality = NameNormalizer.qualityOf(show.name),
                        year = show.year ?: Regex("\\b(?:19|20)\\d{2}\\b").find(show.name)?.value,
                        genre = show.genre, cast = show.cast)
                }
            }

            viewModelScope.launch {
                repository.observeEpisodes(seriesId).collect { episodes ->
                    allEpisodes = episodes
                    emitSeasons(repository.lastSeasonNames)
                    observeVisibleEpisodeSizes(accountId)
                    if (episodes.isNotEmpty()) _state.update {
                        it.copy(isLoading = false, isRefreshing = true)
                    }
                    refreshPlayback()
                }
            }

            viewModelScope.launch {
                playbackState?.isFavourite(TYPE_SERIES, seriesId.toString())?.collect { fav ->
                    _state.update { it.copy(isFavourite = fav) }
                }
            }

            repository.refreshSeriesInfo(seriesId, force = false)
                .onFailure { t -> _state.update { it.copy(error = t.toAppError()) } }
            repository.lastShowMetadata?.let { metadata -> _state.update {
                it.copy(genre = metadata.genre, cast = metadata.cast,
                    year = metadata.year ?: it.year)
            } }
            _state.update { it.copy(isLoading = false, isRefreshing = false) }
        }
    }

    fun selectSeason(number: Int) {
        _state.update { it.copy(selectedSeason = number) }
        emitSeasons(repo?.lastSeasonNames.orEmpty())
        accountId?.let(::observeVisibleEpisodeSizes)
    }

    private fun observeVisibleEpisodeSizes(account: String) {
        sizeJob?.cancel()
        val visibleIds = _state.value.episodes.map { it.episodeId }
        val sizeRepo = MediaFileSizeRepository(db.mediaFileSizeDao())
        sizeJob = viewModelScope.launch {
            sizeRepo.observeEpisodes(account, seriesId, visibleIds).collect { rows ->
                val displays = rows.mapNotNull { row ->
                    FileSizeFormatter.display(row)?.let { row.mediaId to it }
                }.toMap()
                _state.update { it.copy(fileSizes = displays) }
            }
        }
    }

    fun refresh() {
        val repository = repo ?: return
        _state.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            repository.refreshSeriesInfo(seriesId, force = true)
                .onFailure { t -> _state.update { it.copy(error = t.toAppError()) } }
            repository.lastShowMetadata?.let { metadata -> _state.update {
                it.copy(genre = metadata.genre, cast = metadata.cast,
                    year = metadata.year ?: it.year)
            } }
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    suspend fun resumePositionMs(episodeId: String): Long? =
        playbackState?.resumePosition(TYPE_SERIES, episodeId)?.positionMs

    fun clearResume(episodeId: String) {
        viewModelScope.launch { clearResumeNow(episodeId) }
    }

    suspend fun clearResumeNow(episodeId: String) {
        playbackState?.clearResume(TYPE_SERIES, episodeId)
        refreshPlayback()
    }

    fun refreshPlayback() {
        val account = accountId ?: return
        val episodes = allEpisodes.toList()
        viewModelScope.launch(Dispatchers.IO) {
            val ids = episodes.map { it.episodeId }
            val positions = ids.chunked(200).flatMap { db.resumeDao().forItems(account, TYPE_SERIES, it) }
                .associateBy { it.itemId }
            val watched = ids.chunked(200).flatMap { db.watchedDao().forItems(account, TYPE_SERIES, it) }
                .map { it.itemId }.toSet()
            val playback = episodes.associate { episode ->
                val progress = positions[episode.episodeId]
                episode.episodeId to DetailEpisode(
                    episode.episodeId, episode.seasonNumber, episode.episodeNum,
                    progress?.durationMs?.takeIf { it > 0 } ?: episode.durationSecs?.toLong()?.times(1000),
                    progress?.positionMs, progress?.updatedAt ?: 0,
                    episode.episodeId in watched
                )
            }
            val actions = DetailPlaybackPolicy.actions(playback.values.toList())
            _state.update { it.copy(playback = playback, actions = actions) }
            actions.primary?.episodeId?.let(::requestPrimaryEpisodeSizeProbe)
        }
    }

    private fun requestPrimaryEpisodeSizeProbe(episodeId: String) {
        if (playRequested || primaryProbeRequested) return
        val episode = allEpisodes.firstOrNull { it.episodeId == episodeId } ?: return
        val account = accountId ?: return
        val auth = credentials ?: return
        primaryProbeRequested = true
        sizeProbeJob?.cancel()
        sizeProbeJob = viewModelScope.launch {
            val sizes = MediaFileSizeRepository(db.mediaFileSizeDao())
            if (!sizes.shouldProbe(account, "episode", seriesId, episodeId)) {
                NetworkEventRecorder.occurrence(NetworkEventSource.SIZE_PROBE, account,
                    NetworkEventKind.SIZE_SKIPPED, if (sizes.downloadSize(account, "episode", seriesId, episodeId)?.exact == true)
                        "exact known" else "probed within 24h")
                return@launch
            }
            val extension = episode.containerExtension ?: "mkv"
            val result = try {
                sizes.probeWhenIdle(account, "episode", seriesId, episodeId,
                    StreamUrlBuilder.episode(auth, episodeId, extension), HttpFileSizeProbe())
            } catch (cancelled: CancellationException) {
                if (!playRequested) NetworkEventRecorder.occurrence(NetworkEventSource.SIZE_PROBE, account,
                    NetworkEventKind.SIZE_SKIPPED, "playback active")
                throw cancelled
            }
            val (kind, reason) = when (result) {
                is HttpFileSizeProbe.Result.Exact -> NetworkEventKind.SIZE_FOUND to "episode exact"
                is HttpFileSizeProbe.Result.Unknown -> NetworkEventKind.SIZE_NONE to when (result.reason) {
                    HttpFileSizeProbe.Reason.NO_SIZE_HEADER -> "no size header"
                    HttpFileSizeProbe.Reason.INVALID_SIZE_HEADER -> "invalid size header"
                    HttpFileSizeProbe.Reason.UNSUPPORTED_RESPONSE -> "unsupported response"
                }
                is HttpFileSizeProbe.Result.Failed -> NetworkEventKind.SIZE_FAILED to when (result.kind) {
                    HttpFileSizeProbe.Failure.UNAUTHORIZED -> "unauthorized"
                    HttpFileSizeProbe.Failure.FORBIDDEN -> "forbidden"
                    HttpFileSizeProbe.Failure.RATE_LIMITED -> "rate limited"
                    HttpFileSizeProbe.Failure.SERVER -> "server"
                    HttpFileSizeProbe.Failure.TIMEOUT -> "timeout"
                    HttpFileSizeProbe.Failure.NETWORK -> "network"
                }
            }
            NetworkEventRecorder.occurrence(NetworkEventSource.SIZE_PROBE, account, kind, reason)
        }
    }

    /** Called synchronously by the screen before it hands playback to the Activity. */
    fun cancelSizeProbeForPlay() {
        playRequested = true
        if (sizeProbeJob?.isActive == true) NetworkEventRecorder.occurrence(NetworkEventSource.SIZE_PROBE,
            accountId, NetworkEventKind.SIZE_SKIPPED, "play requested")
        sizeProbeJob?.cancel()
        sizeProbeJob = null
    }

    /** Favourites are kept at *show* level: a show is what a viewer follows. */
    fun toggleFavourite(makeFavourite: Boolean) {
        viewModelScope.launch {
            playbackState?.toggleFavourite(TYPE_SERIES, seriesId.toString(), makeFavourite)
        }
    }

    fun dismissDownloadWarning() { _state.update { it.copy(downloadWarning = null) } }

    fun downloadEpisode(episode: EpisodeEntity) = queueEpisodes(listOf(episode))
    fun downloadSeason() = queueEpisodes(_state.value.episodes)

    private fun queueEpisodes(episodes: List<EpisodeEntity>) {
        val account = accountId ?: return
        if (episodes.isEmpty()) return
        viewModelScope.launch {
            val sizes = MediaFileSizeRepository(db.mediaFileSizeDao())
            val existingStates = _state.value.downloadStates
            val planItems = episodes.map { episode ->
                val size = sizes.downloadSize(account, "episode", seriesId, episode.episodeId)
                DownloadPlanner.Item(size?.bytes, size?.exact ?: false,
                    existingStates[episode.episodeId] in setOf("QUEUED", "PAUSED", "DOWNLOADING", "COMPLETED"))
            }
            val unknown = planItems.count { !it.alreadyQueuedOrDone && it.bytes == null }
            if (unknown > 0) { _state.update { it.copy(downloadMessage = "unknown:$unknown") }; return@launch }
            val pending = episodes.filterNot { existingStates[it.episodeId] in setOf("QUEUED", "PAUSED", "DOWNLOADING", "COMPLETED") }
            if (pending.isEmpty()) return@launch
            val repo = DownloadDependencies.repository(getApplication())
            val preferences = DownloadDependencies.preferences(getApplication()).preferences.first()
            val used = repo.usedBytes()
            when (val plan = DownloadPlanner.plan(planItems, preferences.capBytes, used, repo.reservedBytes())) {
                DownloadPlanner.Plan.Ready -> enqueueEpisodes(repo, account, pending)
                is DownloadPlanner.Plan.NeedsConfirmation -> _state.update { it.copy(downloadWarning =
                    DownloadQuota.Evaluation.ExceedsCap(plan.projected, plan.cap, plan.overBy),
                    downloadWarningUsedBytes = used, downloadWarningItemCount = plan.itemCount) }
                is DownloadPlanner.Plan.Blocked -> _state.update { it.copy(downloadMessage = "unknown:${plan.unknownSize}") }
                DownloadPlanner.Plan.AlreadyQueuedOrDone -> Unit
            }
        }
    }

    private suspend fun enqueueEpisodes(repo: DownloadRepository, account: String, episodes: List<EpisodeEntity>) {
        val batch = episodes.map { episode ->
            val ext = episode.containerExtension?.lowercase()?.filter(Char::isLetterOrDigit)?.take(8) ?: "mkv"
            DownloadRequest(account, "episode", seriesId, episode.episodeId, ext,
                "series/$seriesId/${episode.episodeId}.$ext")
        }
        when (val result = repo.enqueue(batch)) {
            is DownloadRepository.EnqueueResult.Accepted -> {
                DownloadController.kick(getApplication())
                _state.update { it.copy(downloadMessage = null, downloadWarning = null) }
            }
            is DownloadRepository.EnqueueResult.Rejected -> {
                val used = repo.usedBytes()
                _state.update { it.copy(downloadWarning = result.evaluation as? DownloadQuota.Evaluation.ExceedsCap,
                    downloadWarningUsedBytes = used, downloadWarningItemCount = episodes.size) }
            }
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
