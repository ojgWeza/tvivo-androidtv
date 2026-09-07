package com.dev.Tvivo.data.repository

import com.dev.Tvivo.data.local.dao.SyncMetaDao
import com.dev.Tvivo.data.local.entities.SyncMetaEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TTL rules, tested against the failure they exist to prevent: tracking freshness
 * per content type while overwriting the whole table silently wipes every other
 * category and keeps it empty for 24 h.
 */
class CachedFetchTest {

    private class FakeSyncMetaDao : SyncMetaDao {
        val rows = mutableMapOf<Triple<String, String, String>, SyncMetaEntity>()

        override suspend fun get(
            accountId: String,
            contentType: String,
            categoryId: String
        ): SyncMetaEntity? = rows[Triple(accountId, contentType, categoryId)]

        override suspend fun oldestStamp(accountId: String, contentType: String): Long? =
            rows.values.filter { it.accountId == accountId && it.contentType == contentType }
                .minOfOrNull { it.lastSyncedAt }

        override suspend fun staleCategories(
            accountId: String,
            contentType: String,
            staleBefore: Long
        ): List<String> =
            rows.values
                .filter {
                    it.accountId == accountId &&
                        it.contentType == contentType &&
                        it.lastSyncedAt < staleBefore
                }
                .map { it.categoryId }

        override suspend fun upsert(row: SyncMetaEntity) {
            rows[Triple(row.accountId, row.contentType, row.categoryId)] = row
        }

        override suspend fun deleteForAccount(accountId: String) {
            rows.keys.filter { it.first == accountId }.forEach { rows.remove(it) }
        }
    }

    private val account = "acct"
    private val type = "vod"

    @Test
    fun `first read is stale and fetches`() = runTest {
        val dao = FakeSyncMetaDao()
        val cache = CachedFetch(dao)
        var fetched = false

        val result = cache.ensureFresh(
            accountId = account,
            contentType = type,
            categoryId = "A",
            now = 1_000L,
            fetch = { fetched = true; listOf("row") },
            write = {}
        )

        assertTrue(result.getOrThrow())
        assertTrue(fetched)
    }

    @Test
    fun `a fresh category is served from cache without a network call`() = runTest {
        val dao = FakeSyncMetaDao()
        val cache = CachedFetch(dao)
        dao.upsert(SyncMetaEntity(account, type, "A", lastSyncedAt = 1_000L, generation = 0))

        var fetched = false
        val result = cache.ensureFresh(
            accountId = account,
            contentType = type,
            categoryId = "A",
            now = 1_000L + 60_000L,
            fetch = { fetched = true; listOf("row") },
            write = {}
        )

        assertFalse(result.getOrThrow())
        assertFalse(fetched)
    }

    @Test
    fun `past the TTL it fetches again`() = runTest {
        val dao = FakeSyncMetaDao()
        val cache = CachedFetch(dao)
        dao.upsert(SyncMetaEntity(account, type, "A", lastSyncedAt = 0L, generation = 0))

        var fetched = false
        cache.ensureFresh(
            accountId = account,
            contentType = type,
            categoryId = "A",
            now = CachedFetch.DEFAULT_TTL_MILLIS + 1,
            fetch = { fetched = true; listOf("row") },
            write = {}
        )

        assertTrue(fetched)
    }

    @Test
    fun `manual refresh ignores a fresh stamp`() = runTest {
        val dao = FakeSyncMetaDao()
        val cache = CachedFetch(dao)
        dao.upsert(SyncMetaEntity(account, type, "A", lastSyncedAt = 1_000L, generation = 0))

        var fetched = false
        cache.ensureFresh(
            accountId = account,
            contentType = type,
            categoryId = "A",
            force = true,
            now = 1_000L,
            fetch = { fetched = true; listOf("row") },
            write = {}
        )

        assertTrue(fetched)
    }

    /**
     * The bug the per-category key exists to prevent: refreshing A must not make B look
     * fresh. With one stamp per content type, opening B after A served an empty list and
     * kept serving it for 24 h.
     */
    @Test
    fun `refreshing one category does not make another look fresh`() = runTest {
        val dao = FakeSyncMetaDao()
        val cache = CachedFetch(dao)

        cache.ensureFresh(
            accountId = account, contentType = type, categoryId = "A",
            now = 1_000L, fetch = { listOf("a") }, write = {}
        )

        var fetchedB = false
        cache.ensureFresh(
            accountId = account, contentType = type, categoryId = "B",
            now = 1_000L, fetch = { fetchedB = true; listOf("b") }, write = {}
        )

        assertTrue("B must still fetch after A was stamped", fetchedB)
    }

    @Test
    fun `the same category on a different account is fetched separately`() = runTest {
        val dao = FakeSyncMetaDao()
        val cache = CachedFetch(dao)

        cache.ensureFresh(
            accountId = "acct1", contentType = type, categoryId = "A",
            now = 1_000L, fetch = { listOf("a") }, write = {}
        )

        var fetchedOther = false
        cache.ensureFresh(
            accountId = "acct2", contentType = type, categoryId = "A",
            now = 1_000L, fetch = { fetchedOther = true; listOf("a") }, write = {}
        )

        assertTrue(fetchedOther)
    }

    /** A fetch that fails must not stamp, or the failure gets cached for 24 h. */
    @Test
    fun `a failed fetch does not stamp and does not write`() = runTest {
        val dao = FakeSyncMetaDao()
        val cache = CachedFetch(dao)
        var written = false

        val result = cache.ensureFresh<String>(
            accountId = account, contentType = type, categoryId = "A", now = 1_000L,
            fetch = { throw IllegalStateException("boom") },
            write = { written = true }
        )

        assertTrue(result.isFailure)
        assertFalse(written)
        assertEquals(null, dao.get(account, type, "A"))
    }

    @Test
    fun `a failed write leaves the category unstamped so it retries`() = runTest {
        val dao = FakeSyncMetaDao()
        val cache = CachedFetch(dao)

        val result = cache.ensureFresh(
            accountId = account, contentType = type, categoryId = "A", now = 1_000L,
            fetch = { listOf("row") },
            write = { throw IllegalStateException("db full") }
        )

        assertTrue(result.isFailure)
        assertEquals(null, dao.get(account, type, "A"))
    }

    @Test
    fun `isStale reports true for an unknown category`() = runTest {
        val cache = CachedFetch(FakeSyncMetaDao())
        assertTrue(cache.isStale(account, type, "never-fetched"))
    }
}
