package com.dev.Tvivo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailurePolicyTest {
    @Test fun httpFailuresKeepDistinctSafeReasons() {
        val cases = mapOf(
            429 to PlaybackFailureKind.TooManyRequests,
            403 to PlaybackFailureKind.ConnectionLimit,
            401 to PlaybackFailureKind.ProviderRefused,
            404 to PlaybackFailureKind.StreamUnavailable,
            418 to PlaybackFailureKind.HttpClientError,
            503 to PlaybackFailureKind.ProviderUnavailable,
        )
        cases.forEach { (code, expected) ->
            assertEquals(expected, PlaybackFailurePolicy.classify(PlaybackAttemptResult.NetworkFailure, code))
        }
    }

    @Test fun localAndStreamFailuresHaveActionableKinds() {
        assertEquals(PlaybackFailureKind.Offline,
            PlaybackFailurePolicy.classify(PlaybackAttemptResult.NetworkFailure, offline = true))
        assertEquals(PlaybackFailureKind.NetworkDrop,
            PlaybackFailurePolicy.classify(PlaybackAttemptResult.NetworkFailure))
        assertEquals(PlaybackFailureKind.BufferingTimeout,
            PlaybackFailurePolicy.classify(PlaybackAttemptResult.Timeout, stalled = true))
        assertEquals(PlaybackFailureKind.EarlyEnd,
            PlaybackFailurePolicy.classify(PlaybackAttemptResult.UnknownFailure, earlyEnd = true))
        assertEquals(PlaybackFailureKind.UnsupportedFormat,
            PlaybackFailurePolicy.classify(PlaybackAttemptResult.DecodeFailure))
        assertEquals(PlaybackFailureKind.Unknown,
            PlaybackFailurePolicy.classify(PlaybackAttemptResult.UnknownFailure))
    }

    @Test fun retryProgressCountsDownAndClampsWithoutChangingRetryBudget() {
        assertEquals(PlaybackRetryProgress(2, 4, 5), PlaybackFailurePolicy.retryProgress(2, 4, 4_001))
        assertEquals(PlaybackRetryProgress(2, 4, 0), PlaybackFailurePolicy.retryProgress(2, 4, 0))
        assertEquals(PlaybackRetryProgress(4, 4, null), PlaybackFailurePolicy.retryProgress(9, 4, -1))
    }
}
