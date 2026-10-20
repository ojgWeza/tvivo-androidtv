package com.dev.Tvivo.playback

object PlaybackDurationPolicy {
    /** A real player timeline supersedes catalog metadata, even when they differ substantially. */
    fun resolve(playerMilliseconds: Long, knownDurationMs: Long): Long =
        playerMilliseconds.takeIf { it > 0 } ?: knownDurationMs.coerceAtLeast(0L)
}

object PlaybackEndPolicy {
    const val END_TOLERANCE_MILLISECONDS = 30_000L
    const val MINIMUM_UNKNOWN_END_MILLISECONDS = 60_000L

    fun isKnownDurationEnd(positionMilliseconds: Long, durationMilliseconds: Long,
                           playWhenReady: Boolean, sourceDrained: Boolean): Boolean =
        playWhenReady && sourceDrained && durationMilliseconds > 0L &&
            positionMilliseconds >= durationMilliseconds

    fun isKnownSourceEof(durationMilliseconds: Long, playWhenReady: Boolean,
                         sourceEof: Boolean, millisecondsWithoutData: Long,
                         positionMilliseconds: Long, bufferedPositionMilliseconds: Long): Boolean =
        durationMilliseconds > 0L && playWhenReady && sourceEof &&
            millisecondsWithoutData >= 5_000L &&
            bufferedPositionMilliseconds - positionMilliseconds <= 2_000L

    fun isGenuineEnd(positionMilliseconds: Long, durationMilliseconds: Long?, ended: Boolean): Boolean {
        if (!ended) return false
        if (durationMilliseconds == null || durationMilliseconds <= 0)
            return positionMilliseconds >= MINIMUM_UNKNOWN_END_MILLISECONDS
        return durationMilliseconds - positionMilliseconds.coerceIn(0, durationMilliseconds) <= END_TOLERANCE_MILLISECONDS
    }
}

object EpisodeCompletion {
    const val TAIL_MILLISECONDS = 15_000L
    private const val MINIMUM_DURATION_FOR_TAIL_MILLISECONDS = 60_000L

    fun isFinished(positionMilliseconds: Long, durationMilliseconds: Long?, ended: Boolean): Boolean =
        if (ended) PlaybackEndPolicy.isGenuineEnd(positionMilliseconds, durationMilliseconds, true)
        else durationMilliseconds != null && durationMilliseconds >= MINIMUM_DURATION_FOR_TAIL_MILLISECONDS &&
            durationMilliseconds - positionMilliseconds.coerceIn(0, durationMilliseconds) <= TAIL_MILLISECONDS
}

object MovieCompletion {
    const val CREDITS_MILLISECONDS = 240_000L
    private const val MINIMUM_DURATION_MILLISECONDS = 300_000L

    fun creditsTailMilliseconds(durationMilliseconds: Long): Long =
        if (durationMilliseconds <= 0) 0 else minOf(CREDITS_MILLISECONDS, (durationMilliseconds * 0.05).toLong())

    fun isFinished(positionMilliseconds: Long, durationMilliseconds: Long?, ended: Boolean): Boolean {
        if (ended) return PlaybackEndPolicy.isGenuineEnd(positionMilliseconds, durationMilliseconds, true)
        if (durationMilliseconds == null || durationMilliseconds < MINIMUM_DURATION_MILLISECONDS) return false
        return durationMilliseconds - positionMilliseconds.coerceIn(0, durationMilliseconds) <=
            creditsTailMilliseconds(durationMilliseconds)
    }
}
