package com.dev.Tvivo.sync

import android.util.Log
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.CatalogSyncEntity
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.remote.LiveStreamParser
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

            if (written == 0) {
                // An empty answer is not a reason to delete the catalog the user already
                // has: a panel that rejects the call answers with an object, which the
                // parser reports as zero rows, and that is indistinguishable here from a
                // genuinely empty catalog. Flipping generations on it would wipe every
                // per-category row.
                setState(STATE_PARTIAL, done = 0, total = 0)
                Log.i(TAG, "vod catalog sync: no rows returned, keeping existing generation")
                return@withContext Result.success(0)
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

    /**
     * The same tier for live, with one difference that is a fact about the panel rather
     * than a design choice: `get_vod_streams` with no `category_id` is **verified** to
     * return the whole catalog, `get_live_streams` is not (`docs/xtream-api-reference.md`).
     *
     * A panel that rejects the call answers with an object rather than an array, which
     * the parser reports as zero rows — indistinguishable from an empty catalog, and in
     * both cases the right move is the same: leave the per-category rows alone, record
     * [STATE_PARTIAL], and let browsing keep working category by category. Only `ALL`,
     * `RECENTLY ADDED` and content-type search need completeness, and they are the ones
     * that must know they don't have it.
     */
    suspend fun syncLive(): Result<Int> = withContext(Dispatchers.IO) {
        val generation = System.currentTimeMillis()
        setState(STATE_INDEXING, done = 0, total = 0, contentType = TYPE_LIVE)

        try {
            val response = api.getLiveStreams(
                credentials.username,
                credentials.password,
                categoryId = null
            )
            if (!response.isSuccessful) {
                setState(STATE_FAILED, done = 0, total = 0, contentType = TYPE_LIVE)
                return@withContext Result.failure(
                    IllegalStateException("HTTP ${response.code()}")
                )
            }

            val body = response.body()
                ?: run {
                    setState(STATE_FAILED, done = 0, total = 0, contentType = TYPE_LIVE)
                    return@withContext Result.failure(IllegalStateException("empty body"))
                }

            var written = 0
            LiveStreamParser.parse(body, accountId, generation) { chunk ->
                currentCoroutineContext().ensureActive()
                db.liveDao().insertAll(chunk)
                written += chunk.size
                setState(STATE_INDEXING, done = written, total = 0, contentType = TYPE_LIVE)
            }

            if (written == 0) {
                // Nothing was written, so there is no new generation to flip to and the
                // existing per-category rows must survive untouched.
                setState(STATE_PARTIAL, done = 0, total = 0, contentType = TYPE_LIVE)
                Log.i(TAG, "live catalog sync: panel did not answer without category_id")
                return@withContext Result.success(0)
            }

            db.liveDao().deleteGenerationsUpTo(accountId, generation - 1)
            setState(STATE_COMPLETE, done = written, total = written, contentType = TYPE_LIVE)
            Log.i(TAG, "live catalog sync complete: $written rows")
            Result.success(written)
        } catch (t: Throwable) {
            setState(STATE_FAILED, done = 0, total = 0, contentType = TYPE_LIVE)
            Log.w(TAG, "live catalog sync failed: ${t.message}")
            Result.failure(t)
        }
    }

    private suspend fun setState(
        state: String,
        done: Int,
        total: Int,
        contentType: String = TYPE_VOD
    ) {
        db.catalogSyncDao().upsert(
            CatalogSyncEntity(
                accountId = accountId,
                contentType = contentType,
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

        /** Per-category rows only: the panel would not answer without a `category_id`. */
        const val STATE_PARTIAL = "partial"
    }
}
