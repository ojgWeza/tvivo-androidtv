package com.dev.Tvivo.data.repository

import com.dev.Tvivo.data.local.dao.MediaFileSizeDao
import com.dev.Tvivo.data.remote.HttpFileSizeProbe
import com.dev.Tvivo.data.local.entities.MediaFileSizeEntity
import com.dev.Tvivo.playback.MediaConnectionCoordinator
import kotlinx.coroutines.flow.Flow
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Persistence policy for serialized, caller-owned movie and episode size probes. */
class MediaFileSizeRepository(
    private val dao: MediaFileSizeDao,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    data class DownloadSize(val bytes: Long, val exact: Boolean)

    /** Exact source metadata always wins; an estimate remains explicitly approximate. */
    suspend fun downloadSize(accountId: String, kind: String, seriesId: Int, mediaId: String): DownloadSize? {
        requireValidIdentity(kind, seriesId, mediaId)
        val row = dao.get(accountId, kind, seriesId, mediaId) ?: return null
        row.exactBytes?.let { return DownloadSize(it, exact = true) }
        row.estimatedBytes?.let { return DownloadSize(it, exact = false) }
        return null
    }

    fun observeMovie(accountId: String, streamId: Int): Flow<List<MediaFileSizeEntity>> =
        dao.observeForMediaIds(accountId, "movie", 0, listOf(streamId.toString()))

    fun observeEpisodes(accountId: String, seriesId: Int, episodeIds: List<String>): Flow<List<MediaFileSizeEntity>> =
        if (episodeIds.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
        else dao.observeForMediaIds(accountId, "episode", seriesId, episodeIds)

    suspend fun shouldProbe(accountId: String, kind: String, seriesId: Int, mediaId: String): Boolean {
        requireValidIdentity(kind, seriesId, mediaId)
        val row = dao.get(accountId, kind, seriesId, mediaId) ?: return true
        if (row.exactBytes != null) return false
        val lastProbeAt = row.lastProbeAt ?: return true
        return clockMillis() - lastProbeAt >= UNKNOWN_REPROBE_INTERVAL_MS
    }

    /** Explicit call site for a future screen/worker; never probes during playback or a download. */
    suspend fun probeWhenIdle(
        accountId: String,
        kind: String,
        seriesId: Int,
        mediaId: String,
        streamUrl: String,
        probe: HttpFileSizeProbe,
        coordinator: MediaConnectionCoordinator = MediaConnectionCoordinator.shared,
    ): HttpFileSizeProbe.Result {
        requireValidIdentity(kind, seriesId, mediaId)
        return try {
            coordinator.withSlot(MediaConnectionCoordinator.Kind.PROBE, kind, accountId) {
                probe.probe(streamUrl, if (kind == "movie") HttpFileSizeProbe.MediaKind.MOVIE
                    else HttpFileSizeProbe.MediaKind.EPISODE, retryConnectFailures = true).also {
                    recordProbeResult(accountId, kind, seriesId, mediaId, it)
                }
            }
        } catch (_: SocketTimeoutException) {
            HttpFileSizeProbe.Result.Failed(HttpFileSizeProbe.Failure.TIMEOUT)
        } catch (_: ConnectException) {
            HttpFileSizeProbe.Result.Failed(HttpFileSizeProbe.Failure.NETWORK)
        }
    }

    /** Call only after [HttpFileSizeProbe.probe] while the app-wide media slot is held. */
    suspend fun recordProbeResult(
        accountId: String,
        kind: String,
        seriesId: Int,
        mediaId: String,
        result: HttpFileSizeProbe.Result
    ) {
        requireValidIdentity(kind, seriesId, mediaId)
        val at = clockMillis()
        when (result) {
            is HttpFileSizeProbe.Result.Exact -> {
                require(result.bytes in 1_000_000L..200_000_000_000L)
                dao.recordExactProbe(accountId, kind, seriesId, mediaId, result.bytes, at)
            }
            is HttpFileSizeProbe.Result.Unknown -> {
                val row = dao.get(accountId, kind, seriesId, mediaId)
                if (row?.exactBytes == null) {
                    dao.recordProbeAttempt(accountId, kind, seriesId, mediaId, at, "unknown:${result.reason.name.lowercase()}")
                }
            }
            is HttpFileSizeProbe.Result.Failed -> Unit // Transient and authorization failures do not stamp.
        }
    }

    private fun requireValidIdentity(kind: String, seriesId: Int, mediaId: String) {
        require(kind == "movie" || kind == "episode") { "Only movies and episodes have file sizes" }
        require(mediaId.isNotBlank())
        require(if (kind == "movie") seriesId == 0 else seriesId > 0)
    }

    companion object {
        const val UNKNOWN_REPROBE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
