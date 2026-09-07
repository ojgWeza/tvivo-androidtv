package com.dev.Tvivo.sync

import android.util.Log
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.CatalogSyncEntity
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.remote.VodStreamParser
import com.dev.Tvivo.data.remote.XtreamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The full-catalog tier. `ALL`, `RECENTLY ADDED` and content-type search all need
 * completeness, which per-category fetching cannot give.
 *
 * Three things it must not do, all of which are structural rather than tuning:
 *
 * 1. **Must not block.** Categories are one fast call, so a content type is browsable in
 *    about a second while this runs behind it. Blocking on data the user does not need
 *    yet would recur every time the 24 h cache expires, not just on first run.
 * 2. **Must not materialise the response.** ~15 MB of JSON becomes 30–50 MB of heap
 *    against a limit that may be 96 MB, while a Paging grid and a bitmap cache are live.
 *    Rows arrive here in chunks from [VodStreamParser] and are never accumulated.
 * 3. **Must not hold one giant write lock.** Delete-then-insert in a single transaction is
 *    right for a 400-item category and wrong for 48,751 rows, where it becomes a
 *    multi-second write that blocks every read and freezes the grid. Instead rows are
 *    written at `generation = N+1` and one small final transaction flips over and deletes
 *    `generation <= N` — the same all-or-nothing property without the lock.
 *
 * Poster art is never downloaded as part of this.
 */
class CatalogSyncer(
    private val db: AppDatabase,
    private val credentials: Credentials,
    private val accountId: String
) {
    private val api = XtreamApiClient.serviceFor(credentials)

    fun observeProgress(contentType: String = TYPE_VOD): Flow<CatalogSyncEntity?> =
        db.catalogSyncDao().observe(accountId, contentType)

    suspend fun syncVod(): Result<Int> = withContext(Dispatchers.IO) {
        val previousGeneration = db.catalogSyncDao().get(accountId, TYPE_VOD)?.done?.toLong() ?: 0L
        val generation = System.currentTimeMillis()

        setState(STATE_INDEXING, done = 0, total = 0)

        try {
            val response = api.getVodStreams(
                credentials.username,
                credentials.password,
                categoryId = null
            )
            if (!response.isSuccessful) {
                setState(STATE_FAILED, done = 0, total = 0)
                return@withContext Result.failure(
                    IllegalStateException("HTTP ${response.code()}")
                )
            }

            val body = response.body()
                ?: run {
                    setState(STATE_FAILED, done = 0, total = 0)
                    return@withContext Result.failure(IllegalStateException("empty body"))
                }

            var written = 0
            VodStreamParser.parse(body, accountId, generation) { chunk ->
                // Cancellation has to be honoured between chunks, or a cancelled sync
                // keeps writing rows for a screen nobody is looking at.
                currentCoroutineContext().ensureActive()
                db.vodDao().insertAll(chunk)
                written += chunk.size
                setState(STATE_INDEXING, done = written, total = 0)
            }

            // One small transaction: everything older than this run goes, atomically.
            db.vodDao().deleteGenerationsUpTo(accountId, generation - 1)

            setState(STATE_COMPLETE, done = written, total = written)
            Log.i(TAG, "vod catalog sync complete: $written rows")
            Result.success(written)
        } catch (t: Throwable) {
            // A failed sync leaves the previous generation intact and browsable rather
            // than half-deleting it.
            setState(STATE_FAILED, done = 0, total = 0)
            Log.w(TAG, "vod catalog sync failed: ${t.message}")
            Result.failure(t)
        }
    }

    private suspend fun setState(state: String, done: Int, total: Int) {
        db.catalogSyncDao().upsert(
            CatalogSyncEntity(
                accountId = accountId,
                contentType = TYPE_VOD,
                state = state,
                done = done,
                total = total,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        private const val TAG = "TvivoCatalogSync"

        const val STATE_NOT_STARTED = "not_started"
        const val STATE_INDEXING = "indexing"
        const val STATE_COMPLETE = "complete"
        const val STATE_FAILED = "failed"
        const val STATE_STALE = "stale"
    }
}
