package com.dev.Tvivo.home

/** Category order is shared with the desktop catalog; personal shelves never enter the sort. */
enum class CatalogSort { MostVisited, AlphabeticalAsc, AlphabeticalDesc, RecentlyAdded }

data class CatalogCategoryOrder(
    val id: String,
    val title: String,
    val panelOrder: Int,
    val visits: Long = 0,
    val lastVisitedAt: Long = 0,
    val latestAdded: Long = 0,
    /** Adapter supplies the same normalized name stored for catalog items. */
    val normalizedName: String = CatalogLandingPolicy.normalizeSortKey(title)
)

data class CatalogItemOrder(
    val id: Int,
    val title: String,
    val added: Long?,
    /** Adapter supplies the persisted normalized key used by catalog/category sorting. */
    val normalizedName: String = CatalogLandingPolicy.normalizeSortKey(title)
)

/** Keeps a restore target tied to the shelf that owned it when navigation began. */
object ShelfRestoreKey {
    fun of(shelfId: String, cardKey: String): String = "$shelfId|$cardKey"
}

enum class CatalogSearchMode { Hidden, FullFolder, MatchingItems }

/** Semantic targets shared by landing policy and the Android adapter. */
enum class VirtualFolderKey { ContinueWatching, RecentlyAdded, RecentlyPlayed, Favorites }

data class PinnedShelfSpec(
    val kind: MyTvivoShelfKind,
    val id: String,
    val title: String,
    val destination: VirtualFolderKey,
    val emptyHintKey: String,
    val contentType: ShelfContentType? = null,
    val emptyRule: EmptyShelfRule = EmptyShelfRule.HideWhenEmpty
)

data class LandingSpec(
    val contentType: ShelfContentType?,
    val pinned: List<PinnedShelfSpec>,
    val sortOptions: List<CatalogSort>,
    val defaultSort: CatalogSort,
    val emptyRule: EmptyShelfRule = EmptyShelfRule.HideWhenEmpty,
    val seeAllRule: SeeAllRule = SeeAllRule.KnownTotalExceedsPreview
) {
    companion object {
        private val categorySorts = listOf(
            CatalogSort.MostVisited, CatalogSort.AlphabeticalAsc,
            CatalogSort.AlphabeticalDesc, CatalogSort.RecentlyAdded
        )

        fun forType(type: ShelfContentType): LandingSpec {
            val kinds = if (type == ShelfContentType.Live) listOf(
                MyTvivoShelfKind.RecentlyPlayed, MyTvivoShelfKind.RecentlyAdded,
                MyTvivoShelfKind.Favorites
            ) else listOf(
                MyTvivoShelfKind.ContinueWatching, MyTvivoShelfKind.RecentlyAdded,
                MyTvivoShelfKind.Favorites
            )
            val pinned = kinds.mapNotNull { kind ->
                MyTvivoShelfDefinitions.all.firstOrNull { it.type == type && it.kind == kind }
                    ?.let { definition ->
                        PinnedShelfSpec(kind, definition.id, titleFor(kind), destinationFor(kind),
                            hintFor(kind), emptyRule = emptyRuleFor(kind))
                    }
            }
            return LandingSpec(type, pinned, categorySorts, CatalogSort.MostVisited)
        }

        fun myTvivo() = LandingSpec(null, listOf(
            MyTvivoShelfKind.ContinueWatching,
            MyTvivoShelfKind.Favorites
        ).map { kind ->
            PinnedShelfSpec(kind, "__home_${kind.name}", titleFor(kind), destinationFor(kind), hintFor(kind))
        } + MyTvivoShelfDefinitions.all.filter { it.kind == MyTvivoShelfKind.RecentlyAdded }.map { definition ->
            PinnedShelfSpec(definition.kind, definition.id, definition.title,
                destinationFor(definition.kind), hintFor(definition.kind), definition.type,
                emptyRuleFor(definition.kind))
        }, emptyList(), CatalogSort.MostVisited)

        private fun titleFor(kind: MyTvivoShelfKind) = when (kind) {
            MyTvivoShelfKind.ContinueWatching -> "Continue watching"
            MyTvivoShelfKind.RecentlyAdded -> "Recently added"
            MyTvivoShelfKind.RecentlyPlayed -> "Last watched"
            MyTvivoShelfKind.Favorites -> "Favourites"
        }

        private fun destinationFor(kind: MyTvivoShelfKind) = when (kind) {
            MyTvivoShelfKind.ContinueWatching -> VirtualFolderKey.ContinueWatching
            MyTvivoShelfKind.RecentlyAdded -> VirtualFolderKey.RecentlyAdded
            MyTvivoShelfKind.RecentlyPlayed -> VirtualFolderKey.RecentlyPlayed
            MyTvivoShelfKind.Favorites -> VirtualFolderKey.Favorites
        }

        private fun hintFor(kind: MyTvivoShelfKind) = when (kind) {
            MyTvivoShelfKind.ContinueWatching -> "continue"
            MyTvivoShelfKind.RecentlyAdded -> "recently-added"
            MyTvivoShelfKind.RecentlyPlayed -> "recently-played"
            MyTvivoShelfKind.Favorites -> "favorites"
        }

        private fun emptyRuleFor(kind: MyTvivoShelfKind) =
            if (kind == MyTvivoShelfKind.RecentlyPlayed) EmptyShelfRule.ShowHintWhenEmpty
            else EmptyShelfRule.HideWhenEmpty
    }
}

enum class EmptyShelfRule { HideWhenEmpty, ShowHintWhenEmpty }
enum class SeeAllRule { KnownTotalExceedsPreview }
enum class ShelfLoadState { Loading, Loaded }
sealed interface ShelfDecision {
    data object Loading : ShelfDecision
    data object Hidden : ShelfDecision
    data object Hint : ShelfDecision
    data class Cards(val showSeeAll: Boolean) : ShelfDecision
}

object LandingShelfPolicy {
    fun decide(
        isFirstRun: Boolean,
        isCore: Boolean,
        loadState: ShelfLoadState,
        displayedCount: Int,
        totalCount: Int?,
        destinationAvailable: Boolean,
        emptyRule: EmptyShelfRule = EmptyShelfRule.HideWhenEmpty
    ): ShelfDecision {
        if (isFirstRun) return ShelfDecision.Hidden
        if (loadState == ShelfLoadState.Loading) return ShelfDecision.Loading
        if (displayedCount == 0) return when (emptyRule) {
            EmptyShelfRule.HideWhenEmpty -> ShelfDecision.Hidden
            EmptyShelfRule.ShowHintWhenEmpty ->
                if (isCore) ShelfDecision.Hint else ShelfDecision.Hidden
        }
        val seeAll = totalCount != null && totalCount > displayedCount && destinationAvailable
        return ShelfDecision.Cards(seeAll)
    }

    fun showSeeAll(totalCount: Int?, displayedCount: Int, destinationAvailable: Boolean) =
        totalCount != null && totalCount > displayedCount && destinationAvailable
}

enum class ShelfVerticalDirection { Up, Down }

/** Adjacent targets are computed from the rows that actually contain cards. */
object ShelfNavigationPolicy {
    fun adjacentNonEmptyShelf(
        nonEmptyShelfIds: List<String>,
        currentShelfId: String,
        direction: ShelfVerticalDirection
    ): String? {
        val index = nonEmptyShelfIds.indexOf(currentShelfId)
        if (index < 0) return null
        val target = index + if (direction == ShelfVerticalDirection.Up) -1 else 1
        return nonEmptyShelfIds.getOrNull(target)
    }
}

object CatalogLandingPolicy {
    fun openedListSortOptions(): List<CatalogSort> = LandingSpec.forType(ShelfContentType.Movie)
        .sortOptions.filterNot { it == CatalogSort.MostVisited }

    fun openedListSort(selected: CatalogSort): CatalogSort =
        if (selected == CatalogSort.MostVisited) CatalogSort.AlphabeticalAsc else selected

    /** Shared fallback for non-Room callers; Android supplies its persisted NameNormalizer key. */
    fun normalizeSortKey(value: String): String = NormalizedNameKey.of(value)

    fun sorted(categories: List<CatalogCategoryOrder>, sort: CatalogSort): List<CatalogCategoryOrder> =
        when (sort) {
            CatalogSort.MostVisited -> categories.sortedWith(compareByDescending<CatalogCategoryOrder> { it.visits }
                .thenByDescending { it.lastVisitedAt }.thenBy { it.panelOrder }
                .thenBy { it.normalizedName }.thenBy { it.id })
            CatalogSort.AlphabeticalAsc -> categories.sortedWith(
                compareBy<CatalogCategoryOrder> { it.normalizedName }.thenBy { it.id })
            CatalogSort.AlphabeticalDesc -> categories.sortedWith(
                compareByDescending<CatalogCategoryOrder> { it.normalizedName }.thenBy { it.id })
            CatalogSort.RecentlyAdded -> categories.sortedWith(compareByDescending<CatalogCategoryOrder> { it.latestAdded }
                .thenBy { it.normalizedName }.thenBy { it.id })
        }

    fun sortedItemIds(items: List<CatalogItemOrder>, sort: CatalogSort): List<Int> = when (sort) {
        CatalogSort.MostVisited, CatalogSort.AlphabeticalAsc ->
            items.sortedWith(compareBy<CatalogItemOrder>({ it.normalizedName }, { it.id }))
                .map { it.id }
        CatalogSort.AlphabeticalDesc ->
            items.sortedWith(compareByDescending<CatalogItemOrder> { it.normalizedName }
                .thenBy { it.id }).map { it.id }
        CatalogSort.RecentlyAdded ->
            items.sortedWith(compareBy<CatalogItemOrder> { it.added == null }
                .thenComparator { left, right -> (right.added ?: 0L).compareTo(left.added ?: 0L) }
                .thenBy { it.id })
                .map { it.id }
    }

    fun searchMode(title: String, query: String, matchingItems: Int): CatalogSearchMode {
        val needle = query.trim()
        if (needle.isEmpty() || title.contains(needle, ignoreCase = true)) return CatalogSearchMode.FullFolder
        return if (matchingItems > 0) CatalogSearchMode.MatchingItems else CatalogSearchMode.Hidden
    }
}
