package com.dev.Tvivo.playback

object PlaybackEndPolicy {
    const val END_TOLERANCE_MILLISECONDS = 30_000L

    fun isGenuineEnd(positionMilliseconds: Long, durationMilliseconds: Long?, ended: Boolean): Boolean =
        ended && durationMilliseconds != null && durationMilliseconds > 0 &&
            durationMilliseconds - positionMilliseconds.coerceIn(0, durationMilliseconds) <= END_TOLERANCE_MILLISECONDS
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
