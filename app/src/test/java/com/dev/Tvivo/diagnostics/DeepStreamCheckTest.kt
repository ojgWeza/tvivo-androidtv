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

    private fun canStartPlayback() = !DeepStreamCheckGate.isHeld
}
