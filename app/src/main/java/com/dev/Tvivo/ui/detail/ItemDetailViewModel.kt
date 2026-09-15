package com.dev.Tvivo.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.data.local.AppDatabase
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
    val isFavourite: Boolean = false,
    /** Non-null once we know there is somewhere to resume from. */
    val resumeFromMs: Long? = null,
    val isLoading: Boolean = true,
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
    private val itemId: Int
) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val store = CredentialsStore(application)

    private val _state = MutableStateFlow(ItemDetailUiState())
    val state: StateFlow<ItemDetailUiState> = _state.asStateFlow()

    private var playback: PlaybackStateRepository? = null

    private val typeKey = when (contentType) {
        ContentType.LIVE -> TYPE_LIVE
        ContentType.SERIES -> TYPE_SERIES
        else -> TYPE_VOD
    }

    init {
        viewModelScope.launch {
            val credentials = store.load() ?: return@launch
            val accountId = AccountIdentity.of(credentials)
            val pb = PlaybackStateRepository(db, accountId)
            playback = pb

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
                                isLoading = false
                            )
                        }
                    } ?: markNotFound()

                else -> {
                    val repo = VodRepository(db, credentials, accountId)
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
                        if (cached == null) {
                            launch {
                                repo.fetchPlot(itemId)?.let { fetched ->
                                    _state.update { it.copy(plot = fetched) }
                                }
                            }
                        }
                    }
                }
            }

            // Live has no resume point, and a *show* resumes per episode rather than as
            // a whole — neither may raise a "continue from" the page cannot honour.
            if (contentType == ContentType.MOVIES) {
                val ms = pb.resumePosition(typeKey, itemId.toString())?.positionMs
                if (ms != null && ms > 0) _state.update { it.copy(resumeFromMs = ms) }
            }

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

    /** Dismissing the resume choice by starting over must also forget the position. */
    fun clearResume() {
        val pb = playback ?: return
        viewModelScope.launch { pb.clearResume(typeKey, itemId.toString()) }
        _state.update { it.copy(resumeFromMs = null) }
    }
}
