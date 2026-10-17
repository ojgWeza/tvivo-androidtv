package com.dev.Tvivo.playback

import kotlin.random.Random

/** The index picker can be supplied by a seeded generator or a fixed parity fixture. */
class NextPlaybackSelector(private val randomIndex: (Int) -> Int = { Random.Default.nextInt(it) }) {
    constructor(random: Random) : this({ random.nextInt(it) })

    private val playedThisRound = mutableSetOf<String>()
    private var playlist: List<String> = emptyList()

    fun selectNext(
        playlist: List<String>,
        currentId: String,
        finishedIds: Set<String>? = null,
        shuffle: Boolean = false,
    ): String? {
        val ids = playlist.distinct()
        if (this.playlist != ids) {
            this.playlist = ids
            playedThisRound.clear()
        }

        val currentIndex = ids.indexOf(currentId)
        if (ids.size < 2 || (!shuffle && currentIndex < 0)) return null
        if (!shuffle) return ids.getOrNull(currentIndex + 1)

        if (currentIndex >= 0) playedThisRound.add(currentId)
        var candidates = ids.filter { it != currentId && it !in playedThisRound }
        if (candidates.isEmpty()) {
            playedThisRound.clear()
            if (currentIndex >= 0) playedThisRound.add(currentId)
            candidates = ids.filter { it != currentId }
        }
        val unfinished = candidates.filter { finishedIds?.contains(it) != true }
        val pool = unfinished.ifEmpty { candidates }
        return pool[randomIndex(pool.size)]
    }

    fun reset() {
        playlist = emptyList()
        playedThisRound.clear()
    }
}
