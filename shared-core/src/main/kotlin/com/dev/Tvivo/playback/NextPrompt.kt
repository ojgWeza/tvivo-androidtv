package com.dev.Tvivo.playback

enum class StreamKind { Live, Movie, Series, Episode }

object NextPrompt {
    const val EPISODE_LEAD_MILLISECONDS = 45_000L
    const val EPISODE_MAXIMUM_DURATION_SHARE = 4L
    const val MINIMUM_MOVIE_LEAD_MILLISECONDS = 30_000L

    fun leadMilliseconds(kind: StreamKind, durationMilliseconds: Long): Long {
        if (durationMilliseconds <= 0) return 0
        return when (kind) {
            StreamKind.Episode -> minOf(EPISODE_LEAD_MILLISECONDS, durationMilliseconds / EPISODE_MAXIMUM_DURATION_SHARE)
            StreamKind.Movie -> maxOf(MINIMUM_MOVIE_LEAD_MILLISECONDS, MovieCompletion.creditsTailMilliseconds(durationMilliseconds))
            else -> 0
        }
    }
}
