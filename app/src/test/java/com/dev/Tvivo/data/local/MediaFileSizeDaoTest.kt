package com.dev.Tvivo.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.data.local.entities.MediaFileSizeEntity
import com.dev.Tvivo.data.remote.HttpFileSizeProbe
import com.dev.Tvivo.data.repository.MediaFileSizeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaFileSizeDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun estimates_and_missing_values_never_overwrite_exact_size() = runTest {
        val dao = db.mediaFileSizeDao()
        val exact = movie("a", exact = 900)
        dao.upsertExact(exact)
        dao.upsertEstimate(movie("a", estimate = 1000))
        dao.upsertEstimate(movie("a", estimate = 1100))
        assertEquals(900L, dao.get("a", "movie", 0, "42")?.exactBytes)
        assertEquals(1100L, dao.get("a", "movie", 0, "42")?.estimatedBytes)
        dao.upsertExact(movie("a", exact = 1200))
        assertEquals(1200L, dao.get("a", "movie", 0, "42")?.exactBytes)
    }

    @Test fun probeRepository_doesNotStampTransientFailure_andUnknownIsBounded() = runTest {
        var now = 100L
        val repository = MediaFileSizeRepository(db.mediaFileSizeDao()) { now }
        repository.recordProbeResult("a", "movie", 0, "42", HttpFileSizeProbe.Result.Failed(HttpFileSizeProbe.Failure.SERVER))
        assertNull(db.mediaFileSizeDao().get("a", "movie", 0, "42"))

        repository.recordProbeResult("a", "movie", 0, "42",
            HttpFileSizeProbe.Result.Unknown(HttpFileSizeProbe.Reason.NO_SIZE_HEADER))
        val absent = db.mediaFileSizeDao().get("a", "movie", 0, "42")!!
        assertEquals(100L, absent.lastProbeAt)
        assertEquals("unknown:no_size_header", absent.lastProbeOutcome)
        assertTrue(!repository.shouldProbe("a", "movie", 0, "42"))
        now += MediaFileSizeRepository.UNKNOWN_REPROBE_INTERVAL_MS
        assertTrue(repository.shouldProbe("a", "movie", 0, "42"))
    }

    @Test fun unknownProbeNeverDowngradesExistingExactSize() = runTest {
        val dao = db.mediaFileSizeDao()
        dao.upsertExact(movie("a", exact = 900))
        var now = 200L
        val repository = MediaFileSizeRepository(dao) { now }
        repository.recordProbeResult("a", "movie", 0, "42",
            HttpFileSizeProbe.Result.Unknown(HttpFileSizeProbe.Reason.NO_SIZE_HEADER))
        var row = dao.get("a", "movie", 0, "42")!!
        assertEquals(900L, row.exactBytes)
        assertNull(row.lastProbeAt)
        repository.recordProbeResult("a", "movie", 0, "42", HttpFileSizeProbe.Result.Exact(1_200_000))
        row = dao.get("a", "movie", 0, "42")!!
        assertEquals(1_200_000L, row.exactBytes)
        assertEquals(200L, row.lastProbeAt)
    }

    @Test fun probe_attempt_keeps_exact_and_batch_reads_are_account_scoped() = runTest {
        val dao = db.mediaFileSizeDao()
        dao.upsertExact(movie("a", exact = 900))
        dao.recordProbeAttempt("a", "movie", 0, "42", 55, "failed")
        dao.upsertExact(movie("b", exact = 800))
        assertEquals(900L, dao.get("a", "movie", 0, "42")?.exactBytes)
        assertEquals("failed", dao.get("a", "movie", 0, "42")?.lastProbeOutcome)
        assertEquals(listOf("a"), dao.getForMediaIds("a", "movie", 0, listOf("42")).map { it.accountId })
        dao.deleteByAccount("a")
        assertNull(dao.get("a", "movie", 0, "42"))
        assertEquals(800L, dao.get("b", "movie", 0, "42")?.exactBytes)
        assertTrue(dao.observeForMediaIds("b", "movie", 0, listOf("42")).first().isNotEmpty())
    }

    @Test fun episode_id_is_string_and_size_survives_catalog_row_replacement() = runTest {
        val dao = db.mediaFileSizeDao()
        val episode = MediaFileSizeEntity("a", "episode", 300, "episode-001", exactBytes = 700)
        dao.upsertExact(episode)
        // This table has no FK to replaceable catalog/episode rows, so their replacement
        // cannot cascade-delete the retained size record.
        db.openHelper.writableDatabase.execSQL("DELETE FROM series_episodes")
        assertEquals(episode, dao.get("a", "episode", 300, "episode-001"))
    }

    private fun movie(account: String, exact: Long? = null, estimate: Long? = null) =
        MediaFileSizeEntity(account, "movie", 0, "42", exactBytes = exact, estimatedBytes = estimate)
}
