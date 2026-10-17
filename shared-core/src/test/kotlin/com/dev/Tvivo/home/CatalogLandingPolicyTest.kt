package com.dev.Tvivo.home

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogLandingPolicyTest {
    @Test fun openedItemSortUsesTheCategoryNameKeyAndStableIds() {
        val items = listOf(
            CatalogItemOrder(9, "Éclair", 100),
            CatalogItemOrder(3, "Eclair", 100),
            CatalogItemOrder(7, "Alpha", null)
        )
        assertEquals(listOf(7, 3, 9),
            CatalogLandingPolicy.sortedItemIds(items, CatalogSort.AlphabeticalAsc))
        assertEquals(listOf(3, 9, 7),
            CatalogLandingPolicy.sortedItemIds(items, CatalogSort.AlphabeticalDesc))
        assertEquals(listOf(3, 9, 7),
            CatalogLandingPolicy.sortedItemIds(items, CatalogSort.RecentlyAdded))
        assertEquals(listOf(7, 3, 9),
            CatalogLandingPolicy.sortedItemIds(items, CatalogSort.MostVisited))
    }

    @Test fun openedItemSortUsesThePersistedNormalizedNameProvidedByTheAdapter() {
        val items = listOf(
            CatalogItemOrder(1, "Zebra HD", null, normalizedName = "alpha"),
            CatalogItemOrder(2, "Alpha", null, normalizedName = "zebra")
        )
        assertEquals(listOf(1, 2),
            CatalogLandingPolicy.sortedItemIds(items, CatalogSort.AlphabeticalAsc))
    }

    private val categories = listOf(
        CatalogCategoryOrder("a", "Zebra", 0, visits = 2, lastVisitedAt = 20, latestAdded = 10),
        CatalogCategoryOrder("b", "Alpha", 1, visits = 2, lastVisitedAt = 30, latestAdded = 40),
        CatalogCategoryOrder("c", "Beta", 2, visits = 1, lastVisitedAt = 40, latestAdded = 20)
    )

    @Test fun mostVisitedBreaksTiesByLatestVisit() {
        assertEquals(listOf("b", "a", "c"),
            CatalogLandingPolicy.sorted(categories, CatalogSort.MostVisited).map { it.id })
    }

    @Test fun alphabeticalOrdersAreIndependentOfVisitOrder() {
        assertEquals(listOf("b", "c", "a"),
            CatalogLandingPolicy.sorted(categories, CatalogSort.AlphabeticalAsc).map { it.id })
        assertEquals(listOf("a", "c", "b"),
            CatalogLandingPolicy.sorted(categories, CatalogSort.AlphabeticalDesc).map { it.id })
    }

    @Test fun categoryAndItemAlphabeticalOrderShareOneNormalizedNameKey() {
        val categoryNames = listOf("Éclair", "  alpha   beta ", "Zebra")
        val itemNames = listOf("Eclair", "Alpha beta", "zebra")
        val expected = listOf("  alpha   beta ", "Éclair", "Zebra")
        assertEquals(expected, CatalogLandingPolicy.sorted(
            categoryNames.mapIndexed { index, title -> CatalogCategoryOrder(index.toString(), title, index) },
            CatalogSort.AlphabeticalAsc
        ).map { it.title })
        assertEquals(listOf(1, 0, 2), CatalogLandingPolicy.sortedItemIds(
            itemNames.mapIndexed { index, title -> CatalogItemOrder(index, title, null) },
            CatalogSort.AlphabeticalAsc
        ))
        assertEquals(CatalogLandingPolicy.normalizeSortKey("Éclair"),
            CatalogLandingPolicy.normalizeSortKey("Eclair"))
        assertEquals(CatalogLandingPolicy.normalizeSortKey("  alpha   beta "),
            CatalogLandingPolicy.normalizeSortKey("alpha beta"))
    }

    @Test fun normalizedNameKeyGoldenCoversAccentsArabicAndWhitespace() {
        assertEquals("eclair cafe", NormalizedNameKey.of("  ÉCLAIR   café  "))
        assertEquals("مسلسل الاختيار", NormalizedNameKey.of("مُسَلْسَل  الاختيار"))
        assertEquals("movie name", NormalizedNameKey.of("Movie Name"))
    }

    @Test fun recentlyAddedUsesNewestCategoryItem() {
        assertEquals(listOf("b", "c", "a"),
            CatalogLandingPolicy.sorted(categories, CatalogSort.RecentlyAdded).map { it.id })
    }

    @Test fun folderTitleMatchShowsFullFolderAndItemMatchNarrowsIt() {
        assertEquals(CatalogSearchMode.FullFolder,
            CatalogLandingPolicy.searchMode("Arabic Drama", "drama", 0))
        assertEquals(CatalogSearchMode.MatchingItems,
            CatalogLandingPolicy.searchMode("Arabic Drama", "hero", 2))
        assertEquals(CatalogSearchMode.Hidden,
            CatalogLandingPolicy.searchMode("Arabic Drama", "hero", 0))
    }

    @Test fun landingSpecOwnsExactPinnedOrderAndSortChoices() {
        assertEquals(listOf(MyTvivoShelfKind.ContinueWatching, MyTvivoShelfKind.RecentlyAdded,
            MyTvivoShelfKind.Favorites),
            LandingSpec.forType(ShelfContentType.Movie).pinned.map { it.kind })
        assertEquals(listOf(MyTvivoShelfKind.RecentlyPlayed, MyTvivoShelfKind.RecentlyAdded,
            MyTvivoShelfKind.Favorites),
            LandingSpec.forType(ShelfContentType.Live).pinned.map { it.kind })
        assertEquals(listOf("__my_continue_movie", "__my_added_movie", "__my_favorites_movie"),
            LandingSpec.forType(ShelfContentType.Movie).pinned.map { it.id })
        assertEquals(listOf("__my_continue_series", "__my_added_series", "__my_favorites_series"),
            LandingSpec.forType(ShelfContentType.Series).pinned.map { it.id })
        assertEquals(listOf("__my_played_live", "__my_added_live", "__my_favorites_live"),
            LandingSpec.forType(ShelfContentType.Live).pinned.map { it.id })
        assertEquals(listOf(CatalogSort.MostVisited, CatalogSort.AlphabeticalAsc,
            CatalogSort.AlphabeticalDesc, CatalogSort.RecentlyAdded),
            LandingSpec.forType(ShelfContentType.Live).sortOptions)
        assertEquals(listOf(CatalogSort.AlphabeticalAsc, CatalogSort.AlphabeticalDesc,
            CatalogSort.RecentlyAdded), CatalogLandingPolicy.openedListSortOptions())
        assertEquals(CatalogSort.AlphabeticalAsc,
            CatalogLandingPolicy.openedListSort(CatalogSort.MostVisited))
        assertEquals(CatalogSort.AlphabeticalDesc,
            CatalogLandingPolicy.openedListSort(CatalogSort.AlphabeticalDesc))
    }

    @Test fun mixedHomeKeepsItsSeparatePinnedOrderAndDestinationKeys() {
        assertEquals(listOf(MyTvivoShelfKind.ContinueWatching, MyTvivoShelfKind.Favorites,
            MyTvivoShelfKind.RecentlyAdded, MyTvivoShelfKind.RecentlyAdded,
            MyTvivoShelfKind.RecentlyAdded), LandingSpec.myTvivo().pinned.map { it.kind })
        assertEquals(listOf("__home_ContinueWatching", "__home_Favorites", "__my_added_movie",
            "__my_added_series", "__my_added_live"), LandingSpec.myTvivo().pinned.map { it.id })
        assertEquals(listOf(VirtualFolderKey.ContinueWatching, VirtualFolderKey.Favorites,
            VirtualFolderKey.RecentlyAdded, VirtualFolderKey.RecentlyAdded,
            VirtualFolderKey.RecentlyAdded), LandingSpec.myTvivo().pinned.map { it.destination })
    }

    @Test fun shelfDecisionSeparatesLoadingHintHiddenCardsAndSeeAllBoundary() {
        assertEquals(ShelfDecision.Hidden, LandingShelfPolicy.decide(true, true,
            ShelfLoadState.Loaded, 0, 0, true))
        assertEquals(ShelfDecision.Loading, LandingShelfPolicy.decide(false, true,
            ShelfLoadState.Loading, 0, 0, true))
        assertEquals(ShelfDecision.Hidden, LandingShelfPolicy.decide(false, true,
            ShelfLoadState.Loaded, 0, 0, true))
        assertEquals(ShelfDecision.Hidden, LandingShelfPolicy.decide(false, false,
            ShelfLoadState.Loaded, 0, 0, true))
        assertEquals(ShelfDecision.Cards(false), LandingShelfPolicy.decide(false, false,
            ShelfLoadState.Loaded, 20, 20, true))
        assertEquals(ShelfDecision.Cards(true), LandingShelfPolicy.decide(false, false,
            ShelfLoadState.Loaded, 20, 21, true))
        assertEquals(ShelfDecision.Cards(false), LandingShelfPolicy.decide(false, false,
            ShelfLoadState.Loaded, 20, 21, false))
        assertEquals(ShelfDecision.Cards(false), LandingShelfPolicy.decide(false, false,
            ShelfLoadState.Loaded, 20, null, true))
        assertEquals(ShelfDecision.Hidden, LandingShelfPolicy.decide(false, true,
            ShelfLoadState.Loaded, 0, 21, true))
        assertEquals(ShelfDecision.Hint, LandingShelfPolicy.decide(false, true,
            ShelfLoadState.Loaded, 0, 0, true, EmptyShelfRule.ShowHintWhenEmpty))
    }

    @Test fun verticalNavigationSkipsEmptyShelvesAndStopsAtTheEdges() {
        val shelves = listOf("continue", "recent", "category")
        assertEquals("recent", ShelfNavigationPolicy.adjacentNonEmptyShelf(
            shelves, "category", ShelfVerticalDirection.Up))
        assertEquals("category", ShelfNavigationPolicy.adjacentNonEmptyShelf(
            shelves, "recent", ShelfVerticalDirection.Down))
        assertEquals(null, ShelfNavigationPolicy.adjacentNonEmptyShelf(
            shelves, "continue", ShelfVerticalDirection.Up))
        assertEquals(null, ShelfNavigationPolicy.adjacentNonEmptyShelf(
            shelves, "category", ShelfVerticalDirection.Down))
        assertEquals(null, ShelfNavigationPolicy.adjacentNonEmptyShelf(
            shelves, "missing", ShelfVerticalDirection.Up))
    }
}
