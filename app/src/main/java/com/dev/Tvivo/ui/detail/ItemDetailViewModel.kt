package com.dev.Tvivo.ui.detail

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.auth.AppErrorException
import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.repository.LiveRepository
import com.dev.Tvivo.data.repository.PlaybackStateRepository
import com.dev.Tvivo.data.repository.SeriesRepository
import com.dev.Tvivo.data.repository.VodRepository
import com.dev.Tvivo.ui.home.ContentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ItemDetailUiState(
    val title: String = "",
    val plot: String? = null,
    val imageUrl: String? = null,
    val extension: String? = null,
    /**
     * Panel rating on a 0–10 scale, null when unrated. Live never carries one — a channel
     * is not a title — and existing cached rows stay null until the catalog next syncs
     * (see `AppDatabase.MIGRATION_4_5`).
     */
    val rating: Double? = null,
    val quality: String? = null,
    val year: String? = null,
    val durationMinutes: Int? = null,
    val durationMs: Long? = null,
    val genre: String? = null,
    val cast: String? = null,
    val trailer: String? = null,
    val isFavourite: Boolean = false,
    /** Non-null once we know there is somewhere to resume from. */
    val resumeFromMs: Long? = null,
    val isLoading: Boolean = true,
    val plotError: AppError? = null,
    /** The row is gone from the catalog — a favourite whose item was removed upstream. */
    val notFound: Boolean = false
)

/**
 * The pre-run page: one item, everything known about it, and the two things a user
 * actually wants to do with it.
 *
 * **Everything here is a Room read.** The catalog sync already stored the title, the
 * poster URL and — since the `plot` column landed — the description, so opening this
 * screen costs no network call and works with the panel unreachable. That is the whole
 * reason it can sit in front of playback without making playback feel slower.
 */
class ItemDetailViewModel(
    application: Application,
    private val contentType: ContentType,
    private val itemId: Int,
    private val credentials: Credentials,
    private val db: AppDatabase = AppDatabase.get(application)
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ItemDetailUiState())
    val state: StateFlow<ItemDetailUiState> = _state.asStateFlow()

    private var playback: PlaybackStateRepository? = null
    private var accountId: String? = null
    private var vodRepository: VodRepository? = null

    private val typeKey = when (contentType) {
        ContentType.LIVE -> TYPE_LIVE
        ContentType.SERIES -> TYPE_SERIES
        else -> TYPE_VOD
    }

    init {
        viewModelScope.launch {
            val accountId = AccountIdentity.of(credentials)
            this@ItemDetailViewModel.accountId = accountId
            val pb = PlaybackStateRepository(db, accountId)
            playback = pb
            if (contentType == ContentType.MOVIES) {
                // Play becomes available when the catalog row loads. Resolve the saved
                // position first so a fast tap after recreation cannot launch at zero.
                val progress = pb.resumePosition(typeKey, itemId.toString())
                Log.i("PlaybackResume", "detail loaded type=$typeKey position=${progress?.positionMs ?: 0L} " +
                    "duration=${progress?.durationMs ?: 0L}")
                _state.update { current -> current.copy(
                    resumeFromMs = progress?.positionMs?.takeIf { it > 0 },
                    durationMs = progress?.durationMs?.takeIf { it > 0 },
                ) }
                launch {
                    db.resumeDao().observe(accountId, typeKey, itemId.toString()).collect { observed ->
                        _state.update { current -> current.copy(
                            resumeFromMs = observed?.positionMs?.takeIf { it > 0 },
                            durationMs = observed?.durationMs?.takeIf { it > 0 },
                            durationMinutes = observed?.durationMs?.takeIf { it > 0 }
                                ?.div(60_000L)?.toInt() ?: current.durationMinutes,
                        ) }
                    }
                }
            }

            when (contentType) {
                ContentType.LIVE -> LiveRepository(db, credentials, accountId)
                    .byId(itemId)
                    ?.let { row ->
                        _state.update {
                            it.copy(
                                title = row.nameDisplay,
                                // A channel has nothing to describe; the page is a
                                // confirmation step, not a synopsis.
                                plot = null,
                                imageUrl = row.streamIcon,
                                quality = NameNormalizer.qualityOf(row.name),
                                extension = row.ext,
                                isLoading = false
                            )
                        }
                    } ?: markNotFound()

                ContentType.SERIES -> SeriesRepository(db, credentials, accountId)
                    .byId(itemId)
                    ?.let { row ->
                        _state.update {
                            it.copy(
                                title = row.nameDisplay,
                                plot = row.plot?.takeIf { p -> p.isNotBlank() },
                                imageUrl = row.streamIcon,
                                rating = row.rating,
                                quality = NameNormalizer.qualityOf(row.name),
                                year = row.year ?: Regex("\\b(?:19|20)\\d{2}\\b").find(row.name)?.value,
                                genre = row.genre,
                                cast = row.cast,
                                isLoading = false
                            )
                        }
                    } ?: markNotFound()

                else -> {
                    val repo = VodRepository(db, credentials, accountId)
                    vodRepository = repo
                    val row = repo.byId(itemId)
                    if (row == null) {
                        markNotFound()
                    } else {
                        val cached = row.plot?.takeIf { p -> p.isNotBlank() }
                        _state.update {
                            it.copy(
                                title = row.nameDisplay,
                                plot = cached,
                                imageUrl = row.streamIcon,
                                extension = row.containerExtension,
                                rating = row.rating,
                                quality = NameNormalizer.qualityOf(row.name),
                                year = row.year ?: Regex("\\b(?:19|20)\\d{2}\\b").find(row.name)?.value,
                                genre = row.genre,
                                cast = row.cast,
                                durationMinutes = row.durationMinutes,
                                trailer = row.trailer,
                                isLoading = false
                            )
                        }
                        // **The one network call this screen makes, and only for films.**
                        // This panel sends no plot on `get_vod_streams` (verified over a
                        // forced re-sync of all 48,780 rows), so `get_vod_info` is the
                        // only route to one. Fired after the page is already on screen
                        // and never awaited by anything the user can see: the poster,
                        // the title and Play are all up, and the description fills in
                        // underneath them or does not.
                        if (cached == null) retryPlot()
                    }
                }
            }

            // Live has no resume point, and a *show* resumes per episode rather than as
            // a whole — neither may raise a "continue from" the page cannot honour.
            launch {
                pb.isFavourite(typeKey, itemId.toString()).collect { fav ->
                    _state.update { it.copy(isFavourite = fav) }
                }
            }
        }
    }

    private fun markNotFound() = _state.update { it.copy(isLoading = false, notFound = true) }

    /**
     * The heart. For a series this favourites the **show**, not an episode — the show is
     * what the user recognises and what the rail's Favourites folder can render a card
     * for.
     */
    fun toggleFavourite() {
        val pb = playback ?: return
        val next = !_state.value.isFavourite
        viewModelScope.launch { pb.toggleFavourite(typeKey, itemId.toString(), next) }
    }

    /** Re-read playback state after returning from the player Activity. */
    fun refreshPlayback() {
        val pb = playback ?: return
        if (contentType != ContentType.MOVIES) return
        viewModelScope.launch {
            val resume = pb.resumePosition(typeKey, itemId.toString())
            _state.update { current -> current.copy(
                resumeFromMs = resume?.positionMs?.takeIf { it > 0 },
                durationMs = resume?.durationMs?.takeIf { it > 0 },
                durationMinutes = resume?.durationMs?.takeIf { it > 0 }
                    ?.div(60_000L)?.toInt() ?: current.durationMinutes
            ) }
        }
    }

    /** Dismissing the resume choice by starting over must also forget the position. */
    fun clearResume() {
        viewModelScope.launch { clearResumeNow() }
    }

    suspend fun clearResumeNow() {
        val pb = playback ?: return
        pb.clearResume(typeKey, itemId.toString())
        _state.update { it.copy(resumeFromMs = null) }
    }

    fun retryPlot() {
        val repo = vodRepository ?: return
        _state.update { it.copy(plotError = null) }
        viewModelScope.launch {
            repo.fetchDetails(itemId)
                .onSuccess { details -> if (details != null) _state.update { current ->
                    current.copy(plot = details.plot ?: current.plot,
                        genre = details.genre, cast = details.cast,
                        year = details.year ?: current.year,
                        durationMinutes = details.durationMinutes ?: current.durationMinutes,
                        trailer = details.trailer)
                } }
                .onFailure { error -> _state.update {
                    it.copy(plotError = (error as? AppErrorException)?.error ?: AppError.Unreachable)
                } }
        }
    }
}
