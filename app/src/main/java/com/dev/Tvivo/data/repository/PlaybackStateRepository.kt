package com.dev.Tvivo.data.repository

import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.FavouriteEntity
import com.dev.Tvivo.data.local.entities.ResumePositionEntity
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.local.entities.WatchedEntity
import com.dev.Tvivo.playback.EpisodeCompletion
import com.dev.Tvivo.playback.MovieCompletion
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Resume positions and favourites. Both are keyed `(account, contentType, itemId)` —
 * `itemId` alone collides across accounts, and a resume position surfacing under a
 * different panel's film is worse than losing it.
 */
class PlaybackStateRepository(
    private val db: AppDatabase,
    private val accountId: String
) {

    suspend fun resumePosition(contentType: String, itemId: String): ResumePositionEntity? =
        withContext(Dispatchers.IO) { db.resumeDao().get(accountId, contentType, itemId) }

    /**
     * Live is excluded by the caller: a live stream has no meaningful resume point.
     *
     * **The two "don't store this" cases are not the same case**, and collapsing them
     * into one `remove` was a data-loss bug. Reaching the completion tail means the user
     * finished the film or episode, so the row *should* go — otherwise Continue watching fills with
     * things nobody wants to resume. But a position below [MIN_TRACKED_MS] only means
     * *this* visit was too short to be worth recording; it says nothing about the forty
     * minutes the user may already have watched. Removing there threw away a real resume
     * point whenever someone opened a film and backed out again within a minute, which
     * is the single most common way to touch a title you are part-way through.
     *
     * Episodes and movies use their shared-core fixed tails and persist a watched mark
     * before removing their resume point.
     * So: too-short declines to *write*, finished *removes*.
     */
    suspend fun savePosition(
        contentType: String,
        itemId: String,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean = false,
        allowCompletion: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val finished = allowCompletion && (when (contentType) {
            TYPE_SERIES -> EpisodeCompletion.isFinished(positionMs, durationMs.takeIf { it > 0 }, ended)
            TYPE_VOD -> MovieCompletion.isFinished(positionMs, durationMs.takeIf { it > 0 }, ended)
            else -> false
        })
        if (finished) {
            db.withTransaction {
                if (contentType == TYPE_SERIES || contentType == TYPE_VOD) db.watchedDao().mark(
                    WatchedEntity(accountId, accountId, contentType, itemId, System.currentTimeMillis())
                )
                db.resumeDao().remove(accountId, contentType, itemId)
            }
            return@withContext
        }
        // Ordinary short visits preserve an older resume; a timed stop records real progress.
        if (positionMs <= 0L || (allowCompletion && positionMs < MIN_TRACKED_MS)) return@withContext
        if (contentType == TYPE_SERIES || contentType == TYPE_VOD)
            db.watchedDao().unmark(accountId, accountId, contentType, itemId)
        db.resumeDao().upsert(
            ResumePositionEntity(
                accountId = accountId,
                contentType = contentType,
                itemId = itemId,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun continueWatching(contentType: String): Flow<List<ResumePositionEntity>> =
        db.resumeDao().continueWatching(accountId, contentType)

    suspend fun clearResume(contentType: String, itemId: String) =
        withContext(Dispatchers.IO) { db.resumeDao().remove(accountId, contentType, itemId) }

    fun isFavourite(contentType: String, itemId: String): Flow<Boolean> =
        db.favouriteDao().isFavourite(accountId, contentType, itemId)

    fun favourites(contentType: String) = db.favouriteDao().observe(accountId, contentType)

    suspend fun toggleFavourite(contentType: String, itemId: String, makeFavourite: Boolean) =
        withContext(Dispatchers.IO) {
            if (makeFavourite) {
                db.favouriteDao().add(
                    FavouriteEntity(
                        accountId = accountId,
                        contentType = contentType,
                        itemId = itemId,
                        addedAt = System.currentTimeMillis()
                    )
                )
            } else {
                db.favouriteDao().remove(accountId, contentType, itemId)
            }
        }

    private companion object {
        const val MIN_TRACKED_MS = 30_000L
    }
}
