package com.dev.Tvivo.data.repository

import com.dev.Tvivo.data.local.dao.SyncMetaDao
import com.dev.Tvivo.data.local.entities.SyncMetaEntity

/**
 * The one place the TTL/transaction mechanism lives. Per-type repositories supply only
 * the API call and the row mapping, so the caching rules cannot drift apart between
 * movies, live and series.
 *
 * Freshness is tracked per **category**, never per content type. Tracking it per type
 * while overwriting the whole table silently wipes every other category: open A, fetch A,
 * overwrite the table, stamp `vod = now`; then open B, find `vod` "fresh", serve Room,
 * and get an empty list that stays empty for 24 h.
 */
class CachedFetch(private val syncMetaDao: SyncMetaDao) {

    suspend fun <T> ensureFresh(
        accountId: String,
        contentType: String,
        categoryId: String,
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
        force: Boolean = false,
        now: Long = System.currentTimeMillis(),
        fetch: suspend () -> List<T>,
        write: suspend (List<T>) -> Unit
    ): Result<Boolean> {
        if (!force) {
            val meta = syncMetaDao.get(accountId, contentType, categoryId)
            val fresh = meta != null && now - meta.lastSyncedAt <= ttlMillis
            if (fresh) return Result.success(false)
        }

        return try {
            val rows = fetch()
            // A fetch that fails mid-way must roll back rather than leave a half-written
            // category, so the write and the stamp belong to the same transaction.
            write(rows)
            syncMetaDao.upsert(
                SyncMetaEntity(
                    accountId = accountId,
                    contentType = contentType,
                    categoryId = categoryId,
                    lastSyncedAt = now,
                    generation = 0
                )
            )
            Result.success(true)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun isStale(
        accountId: String,
        contentType: String,
        categoryId: String,
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val meta = syncMetaDao.get(accountId, contentType, categoryId) ?: return true
        return now - meta.lastSyncedAt > ttlMillis
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}
