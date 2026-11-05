package com.dev.Tvivo.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.data.remote.HttpFileSizeProbe
import com.dev.Tvivo.data.repository.MediaFileSizeRepository
import com.dev.Tvivo.diagnostics.NetworkEvent
import com.dev.Tvivo.diagnostics.NetworkEventKind
import com.dev.Tvivo.diagnostics.NetworkEventSource
import com.dev.Tvivo.diagnostics.NetworkEventStore
import com.dev.Tvivo.playback.MediaConnectionCoordinator
import com.dev.Tvivo.ui.detail.FileSizeFormatter
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SizeProbeJob11bTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun movieProbePolicy_allowsMissingOrExpiredUnknown_andSuppressesExactOrFreshUnknown() = runTest {
        var now = 10_000L
        val dao = db.mediaFileSizeDao()
        val sizes = MediaFileSizeRepository(dao) { now }
        assertTrue(sizes.shouldProbe("a", "movie", 0, "42"))

        dao.recordExactProbe("a", "movie", 0, "43", 1_200_000, now)
        assertFalse(sizes.shouldProbe("a", "movie", 0, "43"))

        dao.recordProbeAttempt("a", "movie", 0, "44", now, "unknown:no_size_header")
        assertFalse(sizes.shouldProbe("a", "movie", 0, "44"))
        now += MediaFileSizeRepository.UNKNOWN_REPROBE_INTERVAL_MS
        assertTrue(sizes.shouldProbe("a", "movie", 0, "44"))
    }

    @Test fun failedProbeDoesNotStamp_andExactProbeUpdatesObservedMovieLabel() = runTest {
        val dao = db.mediaFileSizeDao()
        val sizes = MediaFileSizeRepository(dao) { 50_000L }
        sizes.recordProbeResult("a", "movie", 0, "51",
            HttpFileSizeProbe.Result.Failed(HttpFileSizeProbe.Failure.SERVER))
        assertNull(dao.get("a", "movie", 0, "51"))

        val observed = sizes.observeMovie("a", 52).first()
        assertTrue(observed.isEmpty())
        sizes.recordProbeResult("a", "movie", 0, "52", HttpFileSizeProbe.Result.Exact(1_500_000))
        val label = FileSizeFormatter.display(sizes.observeMovie("a", 52).first().first())
        assertEquals("1.5 MB", label?.text)
        assertEquals(false, label?.isEstimate)
    }

    @Test fun seriesProbeIdentityIsEpisodeStringAndFailureNeverCreatesSeriesIdRecord() = runTest {
        val sizes = MediaFileSizeRepository(db.mediaFileSizeDao())
        sizes.recordProbeResult("a", "episode", 7, "episode-008",
            HttpFileSizeProbe.Result.Failed(HttpFileSizeProbe.Failure.NETWORK))
        assertNull(db.mediaFileSizeDao().get("a", "episode", 7, "episode-008"))
        assertNull(db.mediaFileSizeDao().get("a", "episode", 7, "7"))
    }

    @Test fun coordinatorDoesNotStartQueuedProbeDuringPlayback_andCancelsProbeOnPlaybackStart() = runTest {
        val coordinator = MediaConnectionCoordinator(clockMillis = { testScheduler.currentTime },
            waitMillis = { delay(it) }, jitter = { 0.0 }, quietGapMillis = 0)
        val token = Any()
        coordinator.beginPlaybackSession(token)
        var started = false
        val queued = backgroundScope.launch {
            coordinator.withSlot(MediaConnectionCoordinator.Kind.PROBE, "episode", "a") { started = true }
        }
        runCurrent()
        assertFalse(started)
        assertEquals(1, coordinator.state.value.probesQueued)
        queued.cancel()
        coordinator.endPlaybackSession(token)
        runCurrent()

        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<CancellationException>()
        val running = backgroundScope.launch {
            try {
                coordinator.withSlot(MediaConnectionCoordinator.Kind.PROBE, "movie", "a") {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            } catch (failure: CancellationException) { stopped.complete(failure) }
        }
        entered.await()
        val playbackToken = Any()
        coordinator.beginPlaybackSession(playbackToken)
        runCurrent()
        assertTrue(stopped.await() is MediaConnectionCoordinator.Preempted)
        running.cancel()
        coordinator.endPlaybackSession(playbackToken)
    }

    @Test fun sizeProbeEventsSerializeOnlyBoundedSafeCategories() {
        val events = (listOf("movie exact", "no size header", "unauthorized", "playback active") +
            "https://user:secret@example.invalid?password=secret").mapIndexed { index, phase ->
            NetworkEvent(index.toLong(), NetworkEventSource.SIZE_PROBE,
                listOf(NetworkEventKind.SIZE_FOUND, NetworkEventKind.SIZE_NONE,
                    NetworkEventKind.SIZE_FAILED, NetworkEventKind.SIZE_SKIPPED, NetworkEventKind.SIZE_SKIPPED)[index],
                accountPartition = if (index == 4) "username=secret" else "a".repeat(32), phase = phase)
        }
        val json = NetworkEventStore.encode(events)
        assertFalse(json.contains("http://", ignoreCase = true))
        assertFalse(json.contains("https://", ignoreCase = true))
        assertFalse(json.contains("password", ignoreCase = true))
        assertFalse(json.contains("username", ignoreCase = true))
        assertFalse(json.contains("credential", ignoreCase = true))
        assertFalse(json.contains("example.invalid", ignoreCase = true))
        assertFalse(json.contains("secret", ignoreCase = true))
        listOf("movie exact", "no size header", "unauthorized", "playback active").forEach { assertTrue(json.contains(it)) }
        assertTrue(json.contains("\"account\":\"${"a".repeat(32)}\""))
    }
}
