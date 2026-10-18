package com.dev.Tvivo.playback

import kotlin.math.ceil

/** Safe, provider-neutral reasons to show beside playback recovery controls. */
enum class PlaybackFailureKind {
    Offline, NetworkDrop, TooManyRequests, ConnectionLimit, ProviderRefused,
    StreamUnavailable, ProviderUnavailable, HttpClientError, UnsupportedFormat,
    BufferingTimeout, EarlyEnd, Unknown,
}

data class PlaybackRetryProgress(val attempt: Int, val maxAttempts: Int, val nextInSeconds: Int?)

object PlaybackFailurePolicy {
    fun classify(
        result: PlaybackAttemptResult,
        statusCode: Int? = null,
        offline: Boolean = false,
        stalled: Boolean = false,
        earlyEnd: Boolean = false,
    ): PlaybackFailureKind = when {
        earlyEnd -> PlaybackFailureKind.EarlyEnd
        stalled -> PlaybackFailureKind.BufferingTimeout
        statusCode == 429 -> PlaybackFailureKind.TooManyRequests
        statusCode == 403 -> PlaybackFailureKind.ConnectionLimit
        statusCode == 401 -> PlaybackFailureKind.ProviderRefused
        statusCode == 404 -> PlaybackFailureKind.StreamUnavailable
        statusCode != null && statusCode in 500..599 -> PlaybackFailureKind.ProviderUnavailable
        statusCode != null && statusCode in 400..499 -> PlaybackFailureKind.HttpClientError
        result == PlaybackAttemptResult.UnsupportedMedia || result == PlaybackAttemptResult.DecodeFailure ->
            PlaybackFailureKind.UnsupportedFormat
        offline -> PlaybackFailureKind.Offline
        result == PlaybackAttemptResult.Timeout -> PlaybackFailureKind.BufferingTimeout
        result == PlaybackAttemptResult.NetworkFailure -> PlaybackFailureKind.NetworkDrop
        else -> PlaybackFailureKind.Unknown
    }

    /** The attempt number is one-based; a negative delay means that attempt is underway. */
    fun retryProgress(attempt: Int, maxAttempts: Int, remainingDelayMs: Long): PlaybackRetryProgress {
        val max = PlaybackRetryPolicy.clampAttempts(maxAttempts)
        return PlaybackRetryProgress(
            attempt.coerceIn(1, max), max,
            if (remainingDelayMs < 0) null else ceil(remainingDelayMs / 1_000.0).toInt().coerceAtLeast(0),
        )
    }
}
