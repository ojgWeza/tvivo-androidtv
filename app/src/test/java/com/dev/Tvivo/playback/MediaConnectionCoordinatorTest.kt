package com.dev.Tvivo.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

class MediaConnectionCoordinatorTest {
    @Test fun playbackPreemptsDownloadAndProbeWithTypedCancellation() = runTest {
        for (kind in listOf(MediaConnectionCoordinator.Kind.DOWNLOAD, MediaConnectionCoordinator.Kind.PROBE)) {
            val coordinator = coordinator()
            val stopped = CompletableDeferred<CancellationException>()
            backgroundScope.launch {
                try {
                    coordinator.withSlot(kind, "holder") { awaitCancellation() }
                } catch (failure: CancellationException) {
                    stopped.complete(failure)
                }
            }
            runCurrent()
            assertEquals(kind, coordinator.state.value.owner?.kind)
            val token = Any()
            coordinator.beginPlaybackSession(token)
            runCurrent()
            assertTrue(stopped.await() is MediaConnectionCoordinator.Preempted)
            assertEquals(MediaConnectionCoordinator.Kind.PLAYBACK, coordinator.state.value.owner?.kind)
            coordinator.endPlaybackSession(token)
            assertNull(coordinator.state.value.owner)
        }
    }

    @Test fun userCancellationIsDistinctAndReleasesSlot() = runTest {
        val coordinator = coordinator()
        val stopped = CompletableDeferred<CancellationException>()
        val job = backgroundScope.launch {
            try { coordinator.withSlot(MediaConnectionCoordinator.Kind.DOWNLOAD, "a") { awaitCancellation() } }
            catch (failure: CancellationException) { stopped.complete(failure) }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertFalse(stopped.await() is MediaConnectionCoordinator.Preempted)
        assertNull(coordinator.state.value.owner)
    }

    @Test fun downloadsAndProbesDoNotPreemptAndQueueByPriorityThenFifo() = runTest {
        val coordinator = coordinator()
        val order = mutableListOf<String>()
        val token = Any()
        coordinator.beginPlaybackSession(token)
        listOf(
            MediaConnectionCoordinator.Kind.PROBE to "p1",
            MediaConnectionCoordinator.Kind.DOWNLOAD to "d1",
            MediaConnectionCoordinator.Kind.PROBE to "p2",
            MediaConnectionCoordinator.Kind.DOWNLOAD to "d2",
        ).forEach { (kind, label) ->
            backgroundScope.launch { coordinator.withSlot(kind, label) { order += label } }
        }
        runCurrent()
        assertEquals(2, coordinator.state.value.downloadsQueued)
        assertEquals(2, coordinator.state.value.probesQueued)
        coordinator.endPlaybackSession(token)
        runCurrent()
        assertEquals(listOf("d1", "d2", "p1", "p2"), order)
    }

    @Test fun probeNeverInterruptsRunningDownload() = runTest {
        val coordinator = coordinator()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        backgroundScope.launch {
            coordinator.withSlot(MediaConnectionCoordinator.Kind.DOWNLOAD, "d") {
                order += "download start"
                release.await()
                order += "download end"
            }
        }
        runCurrent()
        backgroundScope.launch {
            coordinator.withSlot(MediaConnectionCoordinator.Kind.PROBE, "p") { order += "probe" }
        }
        runCurrent()
        assertEquals(listOf("download start"), order)
        assertEquals(MediaConnectionCoordinator.Kind.DOWNLOAD, coordinator.state.value.owner?.kind)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("download start", "download end", "probe"), order)
    }

    @Test fun pausedSessionAndOpenSourceKeepBackgroundBlockedUntilSourceCloses() = runTest {
        val coordinator = coordinator()
        val token = Any()
        coordinator.beginPlaybackSession(token)
        // Pause does not end a loaded session; a source may still hold its HTTP socket.
        coordinator.awaitPlaybackReadyBlocking(token)
        var started = false
        backgroundScope.launch { coordinator.withSlot(MediaConnectionCoordinator.Kind.DOWNLOAD, "d") { started = true } }
        runCurrent()
        assertFalse(started)
        coordinator.endPlaybackSession(token)
        runCurrent()
        assertFalse(started)
        coordinator.playbackSourceClosed(token)
        runCurrent()
        assertTrue(started)
    }

    @Test fun quietGapAfterCloseUsesInjectedVirtualClock() = runTest {
        val coordinator = coordinator(quietGap = 6_000)
        val token = Any()
        coordinator.beginPlaybackSession(token)
        coordinator.endPlaybackSession(token)
        var started = false
        backgroundScope.launch { coordinator.withSlot(MediaConnectionCoordinator.Kind.DOWNLOAD, "d") { started = true } }
        runCurrent()
        advanceTimeBy(5_999)
        runCurrent()
        assertFalse(started)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(started)
    }

    @Test fun connectFailuresBackOffAndStopAtAttemptCap() = runTest {
        val coordinator = coordinator(quietGap = 0, attempts = 3)
        val times = mutableListOf<Long>()
        var failure: IOException? = null
        backgroundScope.launch {
            try {
                coordinator.withSlot(MediaConnectionCoordinator.Kind.DOWNLOAD, "d") {
                    times += testScheduler.currentTime
                    throw ConnectException("mock connect timeout")
                }
            } catch (caught: IOException) { failure = caught }
        }
        runCurrent()
        assertEquals(listOf(0L), times)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), times)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L), times)
        assertTrue(failure is ConnectException)
    }

    @Test fun exceptionNeverLeaksSlotAndConcurrentRequestsStayExclusive() = runTest {
        val coordinator = coordinator()
        try { coordinator.withSlot(MediaConnectionCoordinator.Kind.PROBE, "bad") { throw IOException("mock") } }
        catch (_: IOException) { }
        assertNull(coordinator.state.value.owner)
        var active = 0
        var peak = 0
        var completed = 0
        repeat(20) {
            launch {
                coordinator.withSlot(MediaConnectionCoordinator.Kind.DOWNLOAD, "d") {
                    active++
                    peak = maxOf(peak, active)
                    delay(1)
                    active--
                    completed++
                }
            }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(20, completed)
        assertEquals(1, peak)
        assertNull(coordinator.state.value.owner)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        quietGap: Long = 0,
        attempts: Int = 4,
    ) = MediaConnectionCoordinator(
        clockMillis = { testScheduler.currentTime },
        waitMillis = { delay(it) },
        jitter = { 0.0 },
        quietGapMillis = quietGap,
        maxConnectAttempts = attempts,
    )
}
