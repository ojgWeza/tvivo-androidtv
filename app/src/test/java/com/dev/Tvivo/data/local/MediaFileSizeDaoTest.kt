package com.dev.Tvivo.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.data.local.entities.MediaFileSizeEntity
import com.dev.Tvivo.data.remote.HttpFileSizeProbe
import com.dev.Tvivo.data.repository.MediaFileSizeRepository
import com.dev.Tvivo.playback.MediaConnectionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
import java.net.ConnectException

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

    @Test fun probeWhenIdleRecordsExactUnknownAndFailedByExistingPolicy() = runTest {
        val repository = MediaFileSizeRepository(db.mediaFileSizeDao()) { 123L }
        val coordinator = MediaConnectionCoordinator(
            clockMillis = { testScheduler.currentTime }, waitMillis = { delay(it) },
            jitter = { 0.0 }, quietGapMillis = 0,
        )
        val results = listOf(
            HttpFileSizeProbe.Result.Exact(1_200_000),
            HttpFileSizeProbe.Result.Unknown(HttpFileSizeProbe.Reason.NO_SIZE_HEADER),
            HttpFileSizeProbe.Result.Failed(HttpFileSizeProbe.Failure.SERVER),
        )
        results.forEachIndexed { index, expected ->
            val mediaId = (index + 1).toString()
            val probe = object : HttpFileSizeProbe() {
                override suspend fun probe(streamUrl: String, kind: HttpFileSizeProbe.MediaKind,
                    retryConnectFailures: Boolean): HttpFileSizeProbe.Result = expected
            }
            assertEquals(expected, repository.probeWhenIdle("a", "movie", 0, mediaId, "unused", probe, coordinator))
            val row = db.mediaFileSizeDao().get("a", "movie", 0, mediaId)
            when (expected) {
                is HttpFileSizeProbe.Result.Exact -> assertEquals(expected.bytes, row?.exactBytes)
                is HttpFileSizeProbe.Result.Unknown -> assertEquals("unknown:no_size_header", row?.lastProbeOutcome)
                is HttpFileSizeProbe.Result.Failed -> assertNull(row)
            }
        }
    }

    @Test fun probeWhenIdleCancelsBeforeRecordingWhenPlaybackArrives() = runTest {
        val repository = MediaFileSizeRepository(db.mediaFileSizeDao())
        val coordinator = MediaConnectionCoordinator(
            clockMillis = { testScheduler.currentTime }, waitMillis = { delay(it) },
            jitter = { 0.0 }, quietGapMillis = 0,
        )
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<CancellationException>()
        val probe = object : HttpFileSizeProbe() {
            override suspend fun probe(streamUrl: String, kind: HttpFileSizeProbe.MediaKind,
                retryConnectFailures: Boolean): HttpFileSizeProbe.Result {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        backgroundScope.launch {
            try { repository.probeWhenIdle("a", "movie", 0, "99", "unused", probe, coordinator) }
            catch (failure: CancellationException) { stopped.complete(failure) }
        }
        entered.await()
        val token = Any()
        coordinator.beginPlaybackSession(token)
        runCurrent()
        assertTrue(stopped.await() is MediaConnectionCoordinator.Preempted)
        assertNull(db.mediaFileSizeDao().get("a", "movie", 0, "99"))
        coordinator.endPlaybackSession(token)
    }

    @Test fun probeWhenIdleReturnsFailedAfterConnectRetryCap() = runTest {
        val repository = MediaFileSizeRepository(db.mediaFileSizeDao())
        val coordinator = MediaConnectionCoordinator(
            clockMillis = { testScheduler.currentTime }, waitMillis = { delay(it) },
            jitter = { 0.0 }, quietGapMillis = 0, maxConnectAttempts = 3,
        )
        var attempts = 0
        val probe = object : HttpFileSizeProbe() {
            override suspend fun probe(streamUrl: String, kind: HttpFileSizeProbe.MediaKind,
                retryConnectFailures: Boolean): HttpFileSizeProbe.Result {
                attempts++
                throw ConnectException("mock connect failure")
            }
        }
        val result = CompletableDeferred<HttpFileSizeProbe.Result>()
        backgroundScope.launch {
            result.complete(repository.probeWhenIdle("a", "movie", 0, "100", "unused", probe, coordinator))
        }
        testScheduler.advanceUntilIdle()
        assertEquals(HttpFileSizeProbe.Result.Failed(HttpFileSizeProbe.Failure.NETWORK), result.await())
        assertEquals(3, attempts)
        assertNull(db.mediaFileSizeDao().get("a", "movie", 0, "100"))
    }

    private fun movie(account: String, exact: Long? = null, estimate: Long? = null) =
        MediaFileSizeEntity(account, "movie", 0, "42", exactBytes = exact, estimatedBytes = estimate)
}
