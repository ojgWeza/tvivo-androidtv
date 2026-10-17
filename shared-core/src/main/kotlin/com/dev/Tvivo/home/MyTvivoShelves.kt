package com.dev.Tvivo.home

import java.util.PriorityQueue
import java.util.Random
import kotlin.math.ln

enum class ShelfContentType { Movie, Series, Live }
enum class MyTvivoShelfKind { ContinueWatching, RecentlyAdded, RecentlyPlayed, Favorites }

data class MyTvivoShelfDefinition(
    val id: String,
    val title: String,
    val type: ShelfContentType,
    val kind: MyTvivoShelfKind
)

/** Stable type-specific destination IDs. Home combines Continue/Favourites but uses each
 * Recently added definition as its own typed shelf and direct See all destination. */
object MyTvivoShelfDefinitions {
    val all = listOf(
        MyTvivoShelfDefinition("__my_continue_movie", "Continue watching Movies", ShelfContentType.Movie, MyTvivoShelfKind.ContinueWatching),
        MyTvivoShelfDefinition("__my_continue_series", "Continue watching Series", ShelfContentType.Series, MyTvivoShelfKind.ContinueWatching),
        MyTvivoShelfDefinition("__my_added_movie", "Recently added Movies", ShelfContentType.Movie, MyTvivoShelfKind.RecentlyAdded),
        MyTvivoShelfDefinition("__my_added_series", "Recently added Series", ShelfContentType.Series, MyTvivoShelfKind.RecentlyAdded),
        MyTvivoShelfDefinition("__my_added_live", "Recently added Live TV", ShelfContentType.Live, MyTvivoShelfKind.RecentlyAdded),
        MyTvivoShelfDefinition("__my_played_movie", "Recently played Movies", ShelfContentType.Movie, MyTvivoShelfKind.RecentlyPlayed),
        MyTvivoShelfDefinition("__my_played_series", "Recently played Series", ShelfContentType.Series, MyTvivoShelfKind.RecentlyPlayed),
        MyTvivoShelfDefinition("__my_played_live", "Recently played Live TV", ShelfContentType.Live, MyTvivoShelfKind.RecentlyPlayed),
        MyTvivoShelfDefinition("__my_favorites_movie", "Favorite Movies", ShelfContentType.Movie, MyTvivoShelfKind.Favorites),
        MyTvivoShelfDefinition("__my_favorites_series", "Favorite Series", ShelfContentType.Series, MyTvivoShelfKind.Favorites),
        MyTvivoShelfDefinition("__my_favorites_live", "Favorite Live TV", ShelfContentType.Live, MyTvivoShelfKind.Favorites)
    )
}

object HomeRules {
    const val PREVIEW_LIMIT = 20

    /** Core shelves stay visible even when there is no matching saved activity. */
    fun showShelf(core: Boolean, itemCount: Int, firstRun: Boolean): Boolean =
        LandingShelfPolicy.decide(firstRun, core, ShelfLoadState.Loaded, itemCount,
            itemCount, destinationAvailable = false) != ShelfDecision.Hidden

    fun showSeeAll(totalCount: Int, displayedCount: Int, hasDestination: Boolean): Boolean =
        LandingShelfPolicy.showSeeAll(totalCount, displayedCount, hasDestination)

    fun typeBadge(type: ShelfContentType): String = when (type) {
        ShelfContentType.Movie -> "Movie"
        ShelfContentType.Series -> "Series"
        ShelfContentType.Live -> "Live TV"
    }

    /** Every source is already bounded to PREVIEW_LIMIT; merge before taking the visible page. */
    fun <T> mergeRecent(sources: List<List<T>>, timestamp: (T) -> Long, key: (T) -> String): List<T> =
        sources.asSequence().flatten().sortedWith(compareByDescending<T>(timestamp).thenBy(key))
            .distinctBy(key).take(PREVIEW_LIMIT).toList()

    fun isFirstRun(catalogCount: Int, visibleShelfCount: Int): Boolean =
        catalogCount == 0 && visibleShelfCount == 0

    fun progress(positionMs: Long, durationMs: Long): Float? =
        if (durationMs > 0 && positionMs > 0 && positionMs < durationMs)
            (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
        else null

    fun isNew(addedEpochSeconds: Long?, lastVisitedAtMs: Long): Boolean =
        addedEpochSeconds != null && addedEpochSeconds > 0 &&
            addedEpochSeconds > lastVisitedAtMs / 1000
}

/** A-Res weighted reservoir. Callers pass only SQL-bounded candidates. */
object WeightedSuggestions {
    fun <T> sample(
        candidates: Sequence<T>,
        count: Int,
        seed: Long,
        rating: (T) -> Double?,
        excluded: (T) -> Boolean = { false }
    ): List<T> {
        if (count <= 0) return emptyList()
        val random = Random(seed)
        val heap = PriorityQueue<Pair<Double, T>>(compareByDescending { it.first })
        for (item in candidates) {
            if (excluded(item)) continue
            val weight = 1.0 + (rating(item) ?: 0.0).coerceIn(0.0, 10.0) / 10.0
            val key = -ln(1.0 - random.nextDouble()) / weight
            if (heap.size < count) heap.add(key to item)
            else if (key < heap.peek().first) {
                heap.remove()
                heap.add(key to item)
            }
        }
        return heap.toList().sortedBy { it.first }.map { it.second }
    }
}
