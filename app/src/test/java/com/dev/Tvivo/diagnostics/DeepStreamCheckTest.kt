package com.dev.Tvivo.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class DeepStreamCheckTest {
    @After fun releaseGate() { DeepStreamCheckGate.release() }

    @Test fun cancellationReleasesGateAfterStopAcknowledgement() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var clock = 0L
        val runner = DeepStreamCheckRunner(
            stopPlayback = { true },
            readStream = { _, _ -> started.complete(Unit); awaitCancellation() },
            nowElapsedMs = { clock },
            wait = { clock += it },
        )
        val job = async { runner.run("https://redacted.invalid") }
        started.await()
        assertTrue(DeepStreamCheckGate.isHeld)
        job.cancelAndJoin()
        assertFalse(DeepStreamCheckGate.isHeld)
        assertTrue(DeepStreamCheckGate.tryAcquire())
    }

    @Test fun hardTimeoutReleasesGate() = runBlocking {
        val runner = DeepStreamCheckRunner(
            stopPlayback = { false },
            readStream = { _, _ -> awaitCancellation() },
            hardTimeoutMs = 25L,
        )
        try { withTimeout(500) { runner.run("https://redacted.invalid") } } catch (_: Exception) { }
        assertFalse(DeepStreamCheckGate.isHeld)
        assertTrue(DeepStreamCheckGate.tryAcquire())
    }

    @Test fun playbackIsBlockedWhileGateIsHeldAndAllowedAfterRelease() {
        assertTrue(DeepStreamCheckGate.tryAcquire())
        assertFalse(canStartPlayback())
        DeepStreamCheckGate.release()
        assertTrue(canStartPlayback())
    }

    @Test fun partialReadSurvivesExceptionAfterBytes() = runBlocking {
        var reads = 0
        val stream = object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = when (reads++) {
                0 -> { buffer[offset] = 7; 1 }
                1 -> { Thread.sleep(1_100); throw IOException("fixture disconnect") }
                else -> -1
            }
        }
        val started = System.nanoTime()
        val result = measureDeepStreamBody(200, started, started, 2L, stream) { }
        assertEquals(1L, result.bytes)
        assertEquals(200, result.httpStatus)
        assertEquals(1, result.stalls)
        assertEquals(DeepStreamFailure.NETWORK, result.failure)
    }

    @Test fun slowChunkIsReportedAsAStall() = runBlocking {
        var reads = 0
        val stream = object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = when (reads++) {
                0 -> { buffer[offset] = 7; 1 }
                1 -> { Thread.sleep(1_100); buffer[offset] = 8; 1 }
                else -> -1
            }
        }
        val started = System.nanoTime()
        val result = measureDeepStreamBody(200, started, started, 2L, stream) { }
        assertEquals(2L, result.bytes)
        assertTrue("slow chunks should count as a stall", result.stalls > 0)
        assertNotNull(result.stallDurationMs)
        assertEquals(1L, result.bytesAtStall)
    }

    private fun canStartPlayback() = !DeepStreamCheckGate.isHeld
}
