package com.dev.Tvivo.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHandoffTest {
    private class FakePort : PlaybackHandoff.Port {
        val events = mutableListOf<String>()
        var connections = 0
        var peakConnections = 0
        var saveGate: CompletableDeferred<Unit>? = null
        var failSave = false
        val completionFlags = mutableListOf<Boolean>()

        override suspend fun save(item: PlaybackHandoff.Item, ended: Boolean, allowCompletion: Boolean) {
            events += "save:${item.requestId}:$ended"
            completionFlags += allowCompletion
            saveGate?.await()
            if (failSave) error("synthetic save failure")
        }

        override fun close() {
            events += "close"
            connections = 0
        }

        override fun open(item: PlaybackHandoff.Item, generation: Long) {
            events += "open:${item.requestId}"
            connections++
            peakConnections = maxOf(peakConnections, connections)
        }
    }

    private fun item(id: String) = PlaybackHandoff.Item(id, "https://example.invalid/$id.mp4", id,
        "account", "vod", id, false, 0L)

    @Test fun switchSavesAndClosesBeforeOpeningNextConnection() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        val firstGeneration = handoff.generation
        handoff.play(item("second"))
        assertEquals(listOf("close", "open:first", "save:first:false", "close", "open:second"), port.events)
        assertEquals(1, port.peakConnections)
        assertFalse(handoff.isCurrent(firstGeneration))
        assertTrue(handoff.isCurrent(handoff.generation))
    }

    @Test fun activityRecreationWithSameRequestDoesNotReopen() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("same"))
        handoff.play(item("same"))
        assertEquals(listOf("close", "open:same"), port.events)
        assertEquals(1, port.peakConnections)
    }

    @Test fun lateMovieRuntimeEnrichesOnlyCurrentGenerationAndSurvivesReconnect() = runTest {
        val handoff = PlaybackHandoff(FakePort())
        handoff.play(item("first"))
        val first = handoff.generation
        handoff.updateKnownDuration(first, 120_000L)
        assertEquals(120_000L, handoff.current!!.knownDurationMs)
        handoff.reopenCurrent()
        assertEquals(120_000L, handoff.current!!.knownDurationMs)
        handoff.play(item("second"))
        handoff.updateKnownDuration(first, 300_000L)
        assertEquals(0L, handoff.current!!.knownDurationMs)
    }

    @Test fun verifiedEndHandoffSavesFinishedItemAsEndedBeforeOpeningNext() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        handoff.play(item("second"), previousEnded = true)
        assertEquals("save:first:true", port.events[2])
        assertEquals("close", port.events[3])
        assertEquals("open:second", port.events[4])
        assertEquals(1, port.peakConnections)
    }

    @Test fun stopDuringSaveCancelsPendingOpenAndSavesOnce() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        port.saveGate = CompletableDeferred()
        val pending = async { handoff.play(item("second")) }
        testScheduler.runCurrent()
        val stopping = async { handoff.stop() }
        testScheduler.runCurrent()
        port.saveGate!!.complete(Unit)
        pending.await()
        stopping.await()
        assertFalse(port.events.contains("open:second"))
        assertEquals(0, port.connections)
        assertEquals(1, port.peakConnections)
        assertEquals(1, port.events.count { it == "save:first:false" })
    }

    @Test fun oldCallbackIsStaleAsSoonAsSwitchIsRequested() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        val oldGeneration = handoff.generation
        port.saveGate = CompletableDeferred()
        val pending = async { handoff.play(item("second")) }
        testScheduler.runCurrent()
        assertFalse(handoff.isCurrent(oldGeneration))
        port.saveGate!!.complete(Unit)
        pending.await()
        assertTrue(handoff.isCurrent(handoff.generation))
        assertEquals(1, port.peakConnections)
    }

    @Test fun duplicateStopAndLateGenerationAreIgnored() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        val generation = handoff.generation
        handoff.stop()
        handoff.stop()
        assertFalse(handoff.isCurrent(generation))
        assertEquals(1, port.events.count { it == "save:first:false" })
        assertEquals(0, port.connections)
    }

    @Test fun reconnectClosesOldConnectionBeforeReopeningAndMovesGeneration() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        val oldGeneration = handoff.generation
        val newGeneration = handoff.reopenCurrent(12_000)!!
        assertEquals(listOf("close", "open:first", "close", "open:first"), port.events)
        assertEquals(1, port.peakConnections)
        assertFalse(handoff.isCurrent(oldGeneration))
        assertTrue(handoff.isCurrent(newGeneration))
        assertEquals(12_000, handoff.current!!.resumeFromMs)
    }

    @Test fun failedSaveStillClosesOldConnectionWithoutOpeningNext() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        port.failSave = true
        val failure = runCatching { handoff.play(item("second")) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, port.connections)
        assertFalse(port.events.contains("open:second"))
        assertEquals(1, port.peakConnections)
    }

    @Test fun timedStopSavesInProgressAndReleasesTheOnlyConnection() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        handoff.stop(allowCompletion = false)
        assertEquals(listOf("close", "open:first", "save:first:false", "close"), port.events)
        assertEquals(0, port.connections)
        assertEquals(1, port.peakConnections)
        assertEquals(listOf(false), port.completionFlags)
    }

    @Test fun stopFinishesServiceAfterClosingItemEvenIfSaveFails() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        port.failSave = true
        val failure = runCatching {
            handoff.stopAndFinish { port.events += "finish" }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("close", "open:first", "save:first:false", "close", "finish"), port.events)
        assertEquals(0, port.connections)
        assertEquals(null, handoff.current)
    }

    @Test fun stopFinishesServiceAfterSuccessfulClose() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        handoff.stopAndFinish { port.events += "finish" }
        assertEquals(listOf("close", "open:first", "save:first:false", "close", "finish"), port.events)
        assertEquals(null, handoff.current)
        assertEquals(0, port.connections)
    }

    @Test fun stopCompletionWaitsForPositionSaveAndConnectionClose() = runTest {
        val port = FakePort()
        val handoff = PlaybackHandoff(port)
        handoff.play(item("first"))
        port.saveGate = CompletableDeferred()
        val stop = async { handoff.stopAndFinish { port.events += "ack" } }
        testScheduler.runCurrent()
        assertFalse(stop.isCompleted)
        assertFalse(port.events.contains("ack"))
        port.saveGate!!.complete(Unit)
        stop.await()
        assertEquals(listOf("close", "open:first", "save:first:false", "close", "ack"), port.events)
    }
}
