package com.dev.Tvivo.sync

import android.util.Log
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.CatalogSyncEntity
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.remote.LiveStreamParser
import com.dev.Tvivo.data.remote.SeriesListParser
import com.dev.Tvivo.data.remote.VodStreamParser
import com.dev.Tvivo.data.remote.XtreamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

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
 *    Rows arrive here in chunks from the parsers and are never accumulated.
 * 3. **Must not hold one giant write lock.** Delete-then-insert in a single transaction is
 *    right for a 400-item category and wrong for 48,751 rows, where it becomes a
 *    multi-second write that blocks every read and freezes the grid. Instead rows are
 *    written at `generation = N+1` and one small final transaction flips over and deletes
 *    `generation <= N` — the same all-or-nothing property without the lock.
 *
 * All three content types run through one [syncCatalog] body rather than three copies:
 * the zero-row guard below was latent in the VOD path and only found while building live,
 * and a third copy is a third place for it to be got wrong.
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

    suspend fun syncVod(): Result<Int> = syncCatalog(
        contentType = TYPE_VOD,
        request = { api.getVodStreams(credentials.username, credentials.password, categoryId = null) },
        parse = { body, generation, onChunk ->
            VodStreamParser.parse(body, accountId, generation) { chunk ->
                db.vodDao().insertAll(chunk)
                onChunk(chunk.size)
            }
        },
        flip = { generation -> db.vodDao().deleteGenerationsUpTo(accountId, generation - 1) }
    )

    /**
     * The same tier for live, with one difference that is a fact about the panel rather
     * than a design choice: `get_vod_streams` with no `category_id` is **verified** to
     * return the whole catalog, `get_live_streams` and `get_series` are not
     * (`docs/xtream-api-reference.md`). The zero-row guard in [syncCatalog] is what makes
     * that difference survivable rather than destructive.
     */
    suspend fun syncLive(): Result<Int> = syncCatalog(
        contentType = TYPE_LIVE,
        request = { api.getLiveStreams(credentials.username, credentials.password, categoryId = null) },
        parse = { body, generation, onChunk ->
            LiveStreamParser.parse(body, accountId, generation) { chunk ->
                db.liveDao().insertAll(chunk)
                onChunk(chunk.size)
            }
        },
        flip = { generation -> db.liveDao().deleteGenerationsUpTo(accountId, generation - 1) }
    )

    /**
     * Shows only — never episodes. `get_series_info` is per show and fetched lazily by
     * `SeriesRepository`; pulling every show's episodes here would be tens of megabytes
     * for content nobody has opened.
     */
    suspend fun syncSeries(): Result<Int> = syncCatalog(
        contentType = TYPE_SERIES,
        request = { api.getSeries(credentials.username, credentials.password, categoryId = null) },
        parse = { body, generation, onChunk ->
            SeriesListParser.parse(body, accountId, generation) { chunk ->
                db.seriesDao().insertAll(chunk)
                onChunk(chunk.size)
            }
        },
        flip = { generation -> db.seriesDao().deleteGenerationsUpTo(accountId, generation - 1) }
    )

    private suspend fun syncCatalog(
        contentType: String,
        request: suspend () -> Response<ResponseBody>,
        /** Streams the body straight into Room; reports each chunk's size, never its rows. */
        parse: suspend (ResponseBody, Long, suspend (Int) -> Unit) -> Unit,
        flip: suspend (Long) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        val generation = System.currentTimeMillis()
        setState(STATE_INDEXING, done = 0, total = 0, contentType = contentType)

        try {
            val response = request()
            if (!response.isSuccessful) {
                setState(STATE_FAILED, done = 0, total = 0, contentType = contentType)
                return@withContext Result.failure(IllegalStateException("HTTP ${response.code()}"))
            }

            val body = response.body() ?: run {
                setState(STATE_FAILED, done = 0, total = 0, contentType = contentType)
                return@withContext Result.failure(IllegalStateException("empty body"))
            }

            var written = 0
            parse(body, generation) { size ->
                // Cancellation has to be honoured between chunks, or a cancelled sync
                // keeps writing rows for a screen nobody is looking at.
                currentCoroutineContext().ensureActive()
                written += size
                setState(STATE_INDEXING, done = written, total = 0, contentType = contentType)
            }

            if (written == 0) {
                // An empty answer is not a reason to delete the catalog the user already
                // has: a panel that rejects the call answers with an object, which the
                // parser reports as zero rows, and that is indistinguishable here from a
                // genuinely empty catalog. Flipping generations on it would wipe every
                // per-category row. Browsing keeps working category by category; only
                // ALL, RECENTLY ADDED and search need completeness, and they are the ones
                // that must know they do not have it.
                setState(STATE_PARTIAL, done = 0, total = 0, contentType = contentType)
                Log.i(TAG, "$contentType catalog sync: no rows returned, keeping existing generation")
                return@withContext Result.success(0)
            }

            // One small transaction: everything older than this run goes, atomically.
            flip(generation)
            setState(STATE_COMPLETE, done = written, total = written, contentType = contentType)
            Log.i(TAG, "$contentType catalog sync complete: $written rows")
            Result.success(written)
        } catch (t: Throwable) {
            // A failed sync leaves the previous generation intact and browsable rather
            // than half-deleting it.
            setState(STATE_FAILED, done = 0, total = 0, contentType = contentType)
            Log.w(TAG, "$contentType catalog sync failed: ${t.message}")
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
