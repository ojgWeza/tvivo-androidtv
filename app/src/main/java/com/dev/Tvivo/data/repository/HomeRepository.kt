package com.dev.Tvivo.data.repository

import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.home.HomeRules
import com.dev.Tvivo.home.LandingSpec
import com.dev.Tvivo.home.CatalogSort
import com.dev.Tvivo.home.EmptyShelfRule
import com.dev.Tvivo.home.MyTvivoShelfKind
import com.dev.Tvivo.home.ShelfContentType
import com.dev.Tvivo.home.VirtualFolderKey
import com.dev.Tvivo.home.WeightedSuggestions
import com.dev.Tvivo.playback.EpisodeCompletion
import com.dev.Tvivo.ui.browse.BrowseItem
import com.dev.Tvivo.ui.browse.VirtualFolder
import com.dev.Tvivo.ui.browse.toBrowseItem
import com.dev.Tvivo.ui.home.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class HomeCard(
    val type: ContentType,
    val item: BrowseItem,
    val progress: Float? = null,
    val isNew: Boolean = false,
    val resumeItemId: String? = null,
    val activityAt: Long = 0L,
    val typeBadge: String? = null
) {
    val key: String get() = "${type.name}:${item.id}"
}

data class HomeShelf(
    val id: String,
    val title: String,
    val type: ContentType,
    val cards: List<HomeCard>,
    val categoryId: String? = null,
    val totalCount: Int = cards.size,
    val core: Boolean = false,
    val emptyHint: String? = null,
    val hasSeeAllDestination: Boolean = true,
    val typeCounts: Map<ContentType, Int> = emptyMap(),
    val destination: VirtualFolderKey? = null,
    val emptyRule: EmptyShelfRule = EmptyShelfRule.HideWhenEmpty
)

data class HomeSnapshot(val shelves: List<HomeShelf>, val isFirstRun: Boolean)

data class LandingCategoryMetrics(val visits: Long, val lastVisitedAt: Long, val latestAdded: Long)

/** All Home queries are bounded previews; the full catalog stays in Room. */
class HomeRepository(private val db: AppDatabase, private val accountId: String) {
    suspend fun landingPinned(type: ContentType): List<HomeShelf> = withContext(Dispatchers.IO) {
        LandingSpec.forType(type.toShelfContentType()).pinned.map { pinned ->
            val cards = when (pinned.kind) {
                MyTvivoShelfKind.ContinueWatching -> continueCards(type)
                MyTvivoShelfKind.RecentlyAdded -> addedCards(type, pinned.id)
                MyTvivoShelfKind.RecentlyPlayed -> {
                    val rows = db.recentlyPlayedDao().recentSnapshot(accountId, accountId, type.roomKey(), 20)
                    val cardsByKey = resolve(type, rows.mapNotNull { it.itemId.toIntOrNull() })
                        .associateBy { it.item.id.toString() }
                    rows.mapNotNull { row -> cardsByKey[row.itemId]?.copy(activityAt = row.playedAt) }
                }
                MyTvivoShelfKind.Favorites -> {
                    favouriteCards(type)
                }
            }
            HomeShelf(pinned.id, pinned.title, type, cards,
                totalCount = pinnedCount(type, pinned.kind), core = true,
                emptyHint = if (pinned.emptyRule == EmptyShelfRule.ShowHintWhenEmpty)
                    "Watch a channel to see it here" else null,
                destination = pinned.destination, emptyRule = pinned.emptyRule)
        }
    }

    suspend fun landingMetrics(type: ContentType): Map<String, LandingCategoryMetrics> = withContext(Dispatchers.IO) {
        val latest = when (type) {
            ContentType.MOVIES -> db.vodDao().categoryLatest(accountId)
            ContentType.SERIES -> db.seriesDao().categoryLatest(accountId)
            ContentType.LIVE -> db.liveDao().categoryLatest(accountId)
        }.associate { it.categoryId to (it.latestAdded ?: 0L) }
        val visits = db.visitDao().mostVisited(accountId, accountId, type.roomKey()).first()
            .associateBy { it.categoryId }
        (latest.keys + visits.keys).filterNotNull().associateWith { id ->
            val visit = visits[id]
            LandingCategoryMetrics(visit?.count ?: 0, visit?.lastVisitedAt ?: 0,
                latest[id] ?: 0)
        }
    }

    suspend fun landingMatchingCounts(type: ContentType, query: String): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val escaped = NameNormalizer.escapeLikeQuery(query.trim())
            if (escaped.isBlank()) return@withContext emptyMap()
            val rows = when (type) {
                ContentType.MOVIES -> db.vodDao().matchingCategoryCounts(accountId, escaped)
                ContentType.SERIES -> db.seriesDao().matchingCategoryCounts(accountId, escaped)
                ContentType.LIVE -> db.liveDao().matchingCategoryCounts(accountId, escaped)
            }
            rows.mapNotNull { row -> row.categoryId?.let { it to row.count } }.toMap()
        }

    suspend fun landingPreview(type: ContentType, categoryId: String, query: String = "",
                               titleMatches: Boolean = false,
                               sort: CatalogSort = CatalogSort.AlphabeticalAsc): HomeShelf = withContext(Dispatchers.IO) {
        val escaped = NameNormalizer.escapeLikeQuery(query.trim())
        val unfiltered = escaped.isBlank() || titleMatches
        val visitedAt = db.shelfVisitDao().get(accountId, accountId, type.roomKey(), categoryId)
            ?.lastVisitedAt ?: System.currentTimeMillis()
        val cards = when (type) {
            ContentType.MOVIES -> (if (escaped.isBlank() || titleMatches)
                    db.vodDao().landingPreviewSorted(accountId,
                        categoryId.takeUnless { it == "__all" }, sort.toSqlKey())
                else if (categoryId == "__all")
                    db.vodDao().allPreviewFiltered(accountId, escaped)
                else if (unfiltered) db.vodDao().categoryPreview(accountId, categoryId)
                else db.vodDao().categoryPreviewFiltered(accountId, categoryId, escaped))
                .map { HomeCard(type, it.toBrowseItem(), isNew = HomeRules.isNew(it.added, visitedAt)) }
            ContentType.SERIES -> (if (escaped.isBlank() || titleMatches)
                    db.seriesDao().landingPreviewSorted(accountId,
                        categoryId.takeUnless { it == "__all" }, sort.toSqlKey())
                else if (categoryId == "__all") db.seriesDao().allPreviewFiltered(accountId, escaped)
                else if (unfiltered) db.seriesDao().categoryPreview(accountId, categoryId)
                else db.seriesDao().categoryPreviewFiltered(accountId, categoryId, escaped))
                .map { HomeCard(type, it.toBrowseItem(), isNew = HomeRules.isNew(it.added, visitedAt)) }
            ContentType.LIVE -> (if (escaped.isBlank() || titleMatches)
                    db.liveDao().landingPreviewSorted(accountId,
                        categoryId.takeUnless { it == "__all" }, sort.toSqlKey())
                else if (categoryId == "__all") db.liveDao().allPreviewFiltered(accountId, escaped)
                else if (unfiltered) db.liveDao().categoryPreview(accountId, categoryId)
                else db.liveDao().categoryPreviewFiltered(accountId, categoryId, escaped))
                .map { HomeCard(type, it.toBrowseItem(), isNew = HomeRules.isNew(it.added, visitedAt)) }
        }
        HomeShelf(categoryId, if (categoryId == "__all") "ALL" else categoryId, type, cards,
            categoryId)
    }

    suspend fun search(query: String): List<HomeShelf> = withContext(Dispatchers.IO) {
        val normalized = NameNormalizer.normalizeQuery(query.trim())
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        if (normalized.isBlank()) return@withContext emptyList()
        listOf(
            HomeShelf("search-movies", "Movies", ContentType.MOVIES,
                db.vodDao().searchPreview(accountId, normalized).map {
                    HomeCard(ContentType.MOVIES, it.toBrowseItem())
                }),
            HomeShelf("search-series", "Series", ContentType.SERIES,
                db.seriesDao().searchPreview(accountId, normalized).map {
                    HomeCard(ContentType.SERIES, it.toBrowseItem())
                }),
            HomeShelf("search-live", "Live TV", ContentType.LIVE,
                db.liveDao().searchPreview(accountId, normalized).map {
                    HomeCard(ContentType.LIVE, it.toBrowseItem())
                })
        ).filter { it.cards.isNotEmpty() }
    }

    suspend fun load(seed: Int): HomeSnapshot = withContext(Dispatchers.IO) {
        val shelves = mutableListOf<HomeShelf>()
        val recentKeys = mutableSetOf<String>()
        val continueKeys = mutableSetOf<String>()
        for (pinned in LandingSpec.myTvivo().pinned) {
            val kind = pinned.kind
            val types = pinned.contentType?.let { listOf(it.toContentType()) } ?: when (kind) {
                MyTvivoShelfKind.ContinueWatching -> listOf(ContentType.MOVIES, ContentType.SERIES)
                else -> listOf(ContentType.MOVIES, ContentType.SERIES, ContentType.LIVE)
            }
            val sources = types.map { type ->
                val cards = when (kind) {
                    MyTvivoShelfKind.ContinueWatching -> continueCards(type)
                    MyTvivoShelfKind.Favorites -> favouriteCards(type)
                    MyTvivoShelfKind.RecentlyAdded -> addedCards(type, pinned.id)
                    else -> emptyList()
                }
                cards.map { it.copy(typeBadge = HomeRules.typeBadge(type.toShelfContentType())) }
            }
            val cards = if (pinned.contentType != null) sources.singleOrNull().orEmpty().take(20)
                else HomeRules.mergeRecent(sources, HomeCard::activityAt, HomeCard::key)
            val counts = types.associateWith { pinnedCount(it, kind) }
            val shelfType = pinned.contentType?.toContentType() ?: ContentType.MOVIES
            shelves += HomeShelf(pinned.id, pinned.title, shelfType, cards,
                totalCount = counts.values.sum(), core = true,
                emptyHint = null,
                typeCounts = if (pinned.contentType == null) counts else emptyMap(),
                destination = pinned.destination, emptyRule = pinned.emptyRule)
            if (kind == MyTvivoShelfKind.ContinueWatching) continueKeys += cards.map { it.key }
            if (kind == MyTvivoShelfKind.RecentlyAdded) recentKeys += cards.map { it.key }
        }

        val candidates = db.vodDao().suggestionCandidates(accountId, seed)
            .map { HomeCard(ContentType.MOVIES, it.toBrowseItem()) } +
            db.seriesDao().suggestionCandidates(accountId, seed xor 0x51A7)
                .map { HomeCard(ContentType.SERIES, it.toBrowseItem()) }
        val suggestions = WeightedSuggestions.sample(
            candidates.asSequence(), 20, seed.toLong(),
            rating = { it.item.rating },
            excluded = { it.key in continueKeys || it.key in recentKeys }
        ).ifEmpty {
            WeightedSuggestions.sample(candidates.asSequence(), 20, seed.toLong(),
                rating = { it.item.rating })
        }
        if (suggestions.isNotEmpty()) {
            shelves += HomeShelf("__my_suggestions", "Suggestions", ContentType.MOVIES,
                suggestions, hasSeeAllDestination = false)
        }

        for (type in listOf(ContentType.MOVIES, ContentType.SERIES, ContentType.LIVE)) {
            val key = type.roomKey()
            val visits = db.visitDao().topThree(accountId, accountId, key)
            if (visits.isEmpty()) continue
            val names = db.categoryDao().byIds(accountId, key, visits.map { it.categoryId })
                .associateBy { it.categoryId }
            for (visit in visits) {
                val category = names[visit.categoryId] ?: continue
                val lastVisitedAt = db.shelfVisitDao().lastVisitOrNow(
                    accountId, accountId, key, category.categoryId, System.currentTimeMillis())
                val cards = when (type) {
                    ContentType.MOVIES -> db.vodDao().categoryPreview(accountId, category.categoryId)
                        .map { HomeCard(type, it.toBrowseItem(),
                            isNew = HomeRules.isNew(it.added, lastVisitedAt)) }
                    ContentType.SERIES -> db.seriesDao().categoryPreview(accountId, category.categoryId)
                        .map { HomeCard(type, it.toBrowseItem(),
                            isNew = HomeRules.isNew(it.added, lastVisitedAt)) }
                    ContentType.LIVE -> db.liveDao().categoryPreview(accountId, category.categoryId)
                        .map { HomeCard(type, it.toBrowseItem(),
                            isNew = HomeRules.isNew(it.added, lastVisitedAt)) }
                }
                if (cards.isNotEmpty()) shelves += HomeShelf(
                    "__my_folder_${key}_${category.categoryId}", category.name, type, cards,
                    category.categoryId, totalCount = when (type) {
                        ContentType.MOVIES -> db.vodDao().categoryCount(accountId, category.categoryId)
                        ContentType.SERIES -> db.seriesDao().categoryCount(accountId, category.categoryId)
                        ContentType.LIVE -> db.liveDao().categoryCount(accountId, category.categoryId)
                    }
                )
            }
        }
        val catalogCount = db.vodDao().countSnapshot(accountId) +
            db.seriesDao().countSnapshot(accountId) + db.liveDao().countSnapshot(accountId)
        HomeSnapshot(if (catalogCount == 0) emptyList() else shelves,
            HomeRules.isFirstRun(catalogCount, 0))
    }

    private suspend fun addedCards(type: ContentType, shelfId: String): List<HomeCard> {
        val lastVisitedAt = db.shelfVisitDao().lastVisitOrNow(
            accountId, accountId, type.roomKey(), shelfId, System.currentTimeMillis())
        return when (type) {
        ContentType.MOVIES -> db.vodDao().recentlyAdded(accountId, 20)
            .first().map { HomeCard(type, it.toBrowseItem(),
                isNew = HomeRules.isNew(it.added, lastVisitedAt), activityAt = (it.added ?: 0) * 1000) }
        ContentType.SERIES -> db.seriesDao().recentlyAdded(accountId, 20)
            .first().map { HomeCard(type, it.toBrowseItem(),
                isNew = HomeRules.isNew(it.added, lastVisitedAt), activityAt = (it.added ?: 0) * 1000) }
        ContentType.LIVE -> db.liveDao().recentlyAdded(accountId, 20)
            .first().map { HomeCard(type, it.toBrowseItem(),
                isNew = HomeRules.isNew(it.added, lastVisitedAt), activityAt = (it.added ?: 0) * 1000) }
        }
    }

    private suspend fun continueCards(type: ContentType): List<HomeCard> {
        if (type == ContentType.LIVE) return emptyList()
        val rows = db.resumeDao().continueSnapshot(accountId, type.roomKey())
        if (type == ContentType.MOVIES) {
            val byId = db.vodDao().byIds(accountId, rows.mapNotNull { it.itemId.toIntOrNull() })
                .associateBy { it.streamId }
            return rows.mapNotNull { row ->
                val item = row.itemId.toIntOrNull()?.let(byId::get) ?: return@mapNotNull null
                HomeCard(type, item.toBrowseItem(), HomeRules.progress(row.positionMs, row.durationMs),
                    resumeItemId = row.itemId, activityAt = row.updatedAt)
            }
        }
        // Series resume keys are episode IDs, which may be non-numeric. Resolve the
        // episode to its show before opening the existing series detail flow.
        val episodes = db.seriesDao().episodesByIds(accountId, rows.map { it.itemId })
            .associateBy { it.episodeId }
        val shows = db.seriesDao().byIds(accountId, episodes.values.map { it.seriesId })
            .associateBy { it.seriesId }
        return rows.mapNotNull { row ->
            if (EpisodeCompletion.isFinished(row.positionMs, row.durationMs.takeIf { it > 0 }, false))
                return@mapNotNull null
            val episode = episodes[row.itemId] ?: return@mapNotNull null
            val show = shows[episode.seriesId] ?: return@mapNotNull null
            HomeCard(type, show.toBrowseItem(), HomeRules.progress(row.positionMs, row.durationMs),
                resumeItemId = row.itemId, activityAt = row.updatedAt)
        }.distinctBy { it.item.id }
    }

    private suspend fun resolve(type: ContentType, ids: List<Int>): List<HomeCard> {
        if (ids.isEmpty()) return emptyList()
        val rows = when (type) {
            ContentType.MOVIES -> db.vodDao().byIds(accountId, ids).map { HomeCard(type, it.toBrowseItem()) }
            ContentType.SERIES -> db.seriesDao().byIds(accountId, ids).map { HomeCard(type, it.toBrowseItem()) }
            ContentType.LIVE -> db.liveDao().byIds(accountId, ids).map { HomeCard(type, it.toBrowseItem()) }
        }.associateBy { it.item.id }
        return ids.mapNotNull(rows::get)
    }

    private suspend fun favouriteCards(type: ContentType): List<HomeCard> {
        val rows = db.favouriteDao().preview(accountId, type.roomKey())
        val byId = resolve(type, rows.mapNotNull { it.itemId.toIntOrNull() })
            .associateBy { it.item.id.toString() }
        return rows.mapNotNull { row -> byId[row.itemId]?.copy(activityAt = row.addedAt) }
    }

    private suspend fun pinnedCount(type: ContentType, kind: MyTvivoShelfKind): Int = when (kind) {
        MyTvivoShelfKind.ContinueWatching -> if (type == ContentType.LIVE) 0
            else minOf(50, db.resumeDao().continueCount(accountId, type.roomKey()))
        MyTvivoShelfKind.Favorites -> db.favouriteDao().count(accountId, type.roomKey())
        MyTvivoShelfKind.RecentlyPlayed -> minOf(50,
            db.recentlyPlayedDao().count(accountId, accountId, type.roomKey()))
        MyTvivoShelfKind.RecentlyAdded -> when (type) {
            ContentType.MOVIES -> minOf(VirtualFolder.RECENTLY_ADDED_LIMIT,
                db.vodDao().countSnapshot(accountId))
            ContentType.SERIES -> minOf(VirtualFolder.RECENTLY_ADDED_LIMIT,
                db.seriesDao().countSnapshot(accountId))
            ContentType.LIVE -> minOf(VirtualFolder.RECENTLY_ADDED_LIMIT,
                db.liveDao().countSnapshot(accountId))
        }
    }

}

private fun ShelfContentType.toContentType() = when (this) {
    ShelfContentType.Movie -> ContentType.MOVIES
    ShelfContentType.Series -> ContentType.SERIES
    ShelfContentType.Live -> ContentType.LIVE
}

private fun ContentType.toShelfContentType() = when (this) {
    ContentType.MOVIES -> ShelfContentType.Movie
    ContentType.SERIES -> ShelfContentType.Series
    ContentType.LIVE -> ShelfContentType.Live
}

fun ContentType.roomKey(): String = when (this) {
    ContentType.MOVIES -> TYPE_VOD
    ContentType.SERIES -> TYPE_SERIES
    ContentType.LIVE -> TYPE_LIVE
}

private fun CatalogSort.toSqlKey() = when (this) {
    CatalogSort.AlphabeticalDesc -> "za"
    CatalogSort.RecentlyAdded -> "recent"
    CatalogSort.MostVisited, CatalogSort.AlphabeticalAsc -> "az"
}
