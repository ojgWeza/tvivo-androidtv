package com.dev.Tvivo.playback

/** A snapshot of one episode's saved state; no Android or database dependency. */
data class DetailEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val durationMs: Long?,
    val positionMs: Long?,
    val updatedAt: Long = 0,
    val markedWatched: Boolean = false
)

enum class EpisodeRowState { Unstarted, Partial, Watched }
enum class DetailActionKind { Play, Continue, Next, WatchAgain }

data class DetailAction(val kind: DetailActionKind, val episodeId: String, val remainingMs: Long? = null)
data class SeriesActions(val primary: DetailAction?, val secondary: DetailAction? = null)
enum class MovieActionKind { Play, Resume }
data class MovieActions(val primary: MovieActionKind, val showStartOver: Boolean, val remainingMs: Long?)

/**
 * Follows WinUI's SeriesEpisodeResolver in Tvivo.Core/Contracts.cs: sort seasons and
 * episode numbers, prefer the most recent unfinished episode, then the first unwatched
 * episode. Explicit watched state is merged with EpisodeCompletion's fixed tail rule.
 * NextPlaybackSelector supplies the ordered successor; NextPrompt determines when the
 * player offers that successor, not which detail-page button to show.
 */
object DetailPlaybackPolicy {
    fun movieActions(positionMs: Long?, durationMs: Long?): MovieActions {
        val position = positionMs?.takeIf { it > 0 &&
            !MovieCompletion.isFinished(it, durationMs, ended = false) }
            ?: return MovieActions(MovieActionKind.Play, false, null)
        return MovieActions(MovieActionKind.Resume, true,
            durationMs?.takeIf { it > 0 }?.let { (it - position).coerceAtLeast(0L) })
    }

    fun ordered(episodes: List<DetailEpisode>): List<DetailEpisode> =
        episodes.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number }, { it.id }))

    fun rowState(episode: DetailEpisode): EpisodeRowState = when {
        episode.markedWatched || EpisodeCompletion.isFinished(
            episode.positionMs ?: 0L, episode.durationMs, ended = false
        ) -> EpisodeRowState.Watched
        (episode.positionMs ?: 0L) > 0 -> EpisodeRowState.Partial
        else -> EpisodeRowState.Unstarted
    }

    fun actions(episodes: List<DetailEpisode>): SeriesActions {
        val sorted = ordered(episodes)
        if (sorted.isEmpty()) return SeriesActions(null)
        val partial = sorted.filter { rowState(it) == EpisodeRowState.Partial }
            .maxByOrNull { it.updatedAt }
        val lastWatchedIndex = sorted.indexOfLast { rowState(it) == EpisodeRowState.Watched }
        val nextId = if (lastWatchedIndex >= 0) {
            NextPlaybackSelector().selectNext(sorted.map { it.id }, sorted[lastWatchedIndex].id)
        } else null
        val next = nextId?.let { id -> sorted.firstOrNull { it.id == id } }
            ?.takeIf { rowState(it) == EpisodeRowState.Unstarted }
            ?: sorted.firstOrNull { rowState(it) == EpisodeRowState.Unstarted }
        if (partial != null) {
            val remaining = partial.durationMs?.let { (it - (partial.positionMs ?: 0L)).coerceAtLeast(0L) }
            val successor = if (lastWatchedIndex >= 0) {
                NextPlaybackSelector().selectNext(sorted.map { it.id }, partial.id)
                    ?.let { id -> sorted.firstOrNull { it.id == id } }
                    ?.takeIf { rowState(it) == EpisodeRowState.Unstarted }
            } else null
            return SeriesActions(
                DetailAction(DetailActionKind.Continue, partial.id, remaining),
                successor?.let { DetailAction(DetailActionKind.Next, it.id) }
            )
        }
        if (next != null) return SeriesActions(
            DetailAction(if (lastWatchedIndex < 0) DetailActionKind.Play else DetailActionKind.Next, next.id)
        )
        return SeriesActions(DetailAction(DetailActionKind.WatchAgain, sorted.last().id))
    }

    fun remainingLabel(milliseconds: Long): String {
        val minutes = (milliseconds.coerceAtLeast(0L) / 60_000L).coerceAtLeast(1L)
        return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min left" else "$minutes min left"
    }
}
