package com.dev.Tvivo.playback

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PlaybackRetryPolicyTest {
    @Test fun retry_budget_delay_and_timeout_boundaries_match_contract() {
        assertEquals(1, PlaybackRetryPolicy.clampAttempts(0))
        assertEquals(10, PlaybackRetryPolicy.clampAttempts(11))
        assertEquals(25_000L, PlaybackRetryPolicy.attemptTimeoutMs(false))
        assertEquals(10_000L, PlaybackRetryPolicy.attemptTimeoutMs(true))
        assertEquals(listOf(500L, 1_000L, 2_000L, 3_000L), (1..4).map(PlaybackRetryPolicy::delayMs))
        assertTrue(PlaybackRetryPolicy.shouldRetry(PlaybackAttemptResult.Timeout, 4, 5, 0))
        assertFalse(PlaybackRetryPolicy.shouldRetry(PlaybackAttemptResult.Timeout, 5, 5, 0))
    }

    @Test fun not_found_elapsed_budget_and_non_retryable_failures_are_bounded() {
        assertTrue(PlaybackRetryPolicy.shouldRetry(PlaybackAttemptResult.NotFound, 1, 5, 0))
        assertTrue(PlaybackRetryPolicy.shouldRetry(PlaybackAttemptResult.NotFound, 1, 10, 6_500))
        assertFalse(PlaybackRetryPolicy.shouldRetry(PlaybackAttemptResult.NotFound, 1, 10, 6_501))
        assertFalse(PlaybackRetryPolicy.shouldRetry(PlaybackAttemptResult.Forbidden, 1, 5, 0))
        assertFalse(PlaybackRetryPolicy.shouldRetry(PlaybackAttemptResult.UnsupportedMedia, 1, 5, 0))
        assertFalse(PlaybackRetryPolicy.shouldRetry(PlaybackAttemptResult.Cancelled, 1, 5, 0))
    }

    @Test fun transient_failure_kinds_retry_but_only_inside_attempt_limit() {
        listOf(PlaybackAttemptResult.NetworkFailure, PlaybackAttemptResult.Timeout,
            PlaybackAttemptResult.HostNotReady, PlaybackAttemptResult.UnknownFailure).forEach { result ->
            assertTrue(PlaybackRetryPolicy.shouldRetry(result, 1, 5, 0))
            assertFalse(PlaybackRetryPolicy.shouldRetry(result, 5, 5, 0))
        }
    }

    @Test fun midPlayHttpRejectionsUseBoundedReopenBudget() {
        for (status in listOf(401, 403, 429)) {
            val failure = PlaybackRetryPolicy.httpSourceFailure(status)
            assertEquals(PlaybackAttemptResult.HostNotReady, failure)
            assertTrue(PlaybackRetryPolicy.mayReopenAfterProgress(failure))
            assertTrue(PlaybackRetryPolicy.shouldRetry(failure, 1, 5, 0))
            assertFalse(PlaybackRetryPolicy.shouldRetry(failure, 5, 5, 0))
        }
        assertEquals(PlaybackAttemptResult.NotFound, PlaybackRetryPolicy.httpSourceFailure(404))
        assertFalse(PlaybackRetryPolicy.mayReopenAfterProgress(PlaybackAttemptResult.DecodeFailure))
    }
}
