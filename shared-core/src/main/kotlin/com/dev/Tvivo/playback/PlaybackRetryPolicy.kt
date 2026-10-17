package com.dev.Tvivo.playback

import kotlin.math.max

enum class PlaybackAttemptResult { NotFound, NetworkFailure, Timeout, HostNotReady, UnknownFailure, Forbidden, UnsupportedMedia, DecodeFailure, HostFailure, Cancelled }

/** Bounded open retries matching the WinUI player contract. attemptIndex is one-based. */
object PlaybackRetryPolicy {
    const val DEFAULT_ATTEMPTS = 5
    const val MINIMUM_ATTEMPTS = 1
    const val MAXIMUM_ATTEMPTS = 10
    const val MAX_NOT_FOUND_ELAPSED_MS = 8_000L
    const val MAX_NOT_FOUND_HARD_ELAPSED_MS = 10_000L
    const val MIN_NOT_FOUND_OPEN_WINDOW_MS = 1_000L

    fun clampAttempts(attempts: Int) = attempts.coerceIn(MINIMUM_ATTEMPTS, MAXIMUM_ATTEMPTS)
    fun httpSourceFailure(statusCode: Int): PlaybackAttemptResult = when (statusCode) {
        401, 403, 429 -> PlaybackAttemptResult.HostNotReady
        404 -> PlaybackAttemptResult.NotFound
        else -> PlaybackAttemptResult.NetworkFailure
    }
    fun mayReopenAfterProgress(result: PlaybackAttemptResult): Boolean =
        result == PlaybackAttemptResult.HostNotReady || result == PlaybackAttemptResult.NetworkFailure
    fun attemptTimeoutMs(isLive: Boolean) = if (isLive) 10_000L else 25_000L
    fun delayMs(attemptIndex: Int) = when {
        attemptIndex <= 1 -> 500L
        attemptIndex == 2 -> 1_000L
        attemptIndex == 3 -> 2_000L
        else -> 3_000L
    }

    fun shouldRetry(result: PlaybackAttemptResult, attemptIndex: Int, maxAttempts: Int, elapsedMs: Long): Boolean {
        if (attemptIndex < 1 || attemptIndex >= clampAttempts(maxAttempts)) return false
        if (result !in setOf(PlaybackAttemptResult.NotFound, PlaybackAttemptResult.NetworkFailure,
                PlaybackAttemptResult.Timeout, PlaybackAttemptResult.HostNotReady, PlaybackAttemptResult.UnknownFailure)) return false
        return result != PlaybackAttemptResult.NotFound ||
            max(0L, elapsedMs) + delayMs(attemptIndex) + MIN_NOT_FOUND_OPEN_WINDOW_MS <= MAX_NOT_FOUND_ELAPSED_MS
    }
}
