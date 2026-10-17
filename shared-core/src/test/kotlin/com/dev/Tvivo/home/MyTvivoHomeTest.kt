package com.dev.Tvivo.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyTvivoHomeTest {
    private data class Recent(val id: String, val at: Long)

    @Test fun mergedCoreShelvesUseRecencyAcrossTypesAndStayBounded() {
        val movies = (1..20).map { Recent("movie:$it", it.toLong()) }
        val series = listOf(Recent("series:1", 100), Recent("movie:20", 19))
        val merged = HomeRules.mergeRecent(listOf(movies, series), Recent::at, Recent::id)
        assertEquals(20, merged.size)
        assertEquals("series:1", merged.first().id)
        assertEquals(20, merged.map { it.id }.distinct().size)
        assertEquals("Movie", HomeRules.typeBadge(ShelfContentType.Movie))
        assertEquals("Series", HomeRules.typeBadge(ShelfContentType.Series))
        assertEquals("Live TV", HomeRules.typeBadge(ShelfContentType.Live))
    }

    @Test fun emptyAndSeeAllRulesRespectDestinations() {
        assertFalse(HomeRules.showShelf(core = true, itemCount = 0, firstRun = false))
        assertFalse(HomeRules.showShelf(core = false, itemCount = 0, firstRun = false))
        assertFalse(HomeRules.showShelf(core = true, itemCount = 0, firstRun = true))
        assertFalse(HomeRules.showSeeAll(3, 3, true))
        assertTrue(HomeRules.showSeeAll(21, 20, true))
        assertFalse(HomeRules.showSeeAll(21, 20, false))
    }

    @Test fun mixedHomeUsesSharedSpecAndKeepsFirstRunRouteTiles() {
        val spec = LandingSpec.myTvivo()
        assertEquals(listOf("__home_ContinueWatching", "__home_Favorites", "__my_added_movie",
            "__my_added_series", "__my_added_live"),
            spec.pinned.map { it.id })
        assertEquals(listOf("Continue watching", "Favourites", "Recently added Movies",
            "Recently added Series", "Recently added Live TV"),
            spec.pinned.map { it.title })
        assertEquals(listOf(EmptyShelfRule.HideWhenEmpty, EmptyShelfRule.HideWhenEmpty,
            EmptyShelfRule.HideWhenEmpty, EmptyShelfRule.HideWhenEmpty,
            EmptyShelfRule.HideWhenEmpty), spec.pinned.map { it.emptyRule })
        assertEquals(EmptyShelfRule.ShowHintWhenEmpty,
            LandingSpec.forType(ShelfContentType.Live).pinned.first().emptyRule)
        assertEquals(ShelfDecision.Hidden, LandingShelfPolicy.decide(
            isFirstRun = true, isCore = true, loadState = ShelfLoadState.Loaded,
            displayedCount = 0, totalCount = 0, destinationAvailable = true))
        assertEquals(ShelfDecision.Hidden, LandingShelfPolicy.decide(
            isFirstRun = false, isCore = true, loadState = ShelfLoadState.Loaded,
            displayedCount = 0, totalCount = 0, destinationAvailable = true))
    }

    @Test fun shelfDefinitionsMatchWindowsOrderAndIds() {
        assertEquals(listOf(
            "__my_continue_movie", "__my_continue_series",
            "__my_added_movie", "__my_added_series", "__my_added_live",
            "__my_played_movie", "__my_played_series", "__my_played_live",
            "__my_favorites_movie", "__my_favorites_series", "__my_favorites_live"
        ), MyTvivoShelfDefinitions.all.map { it.id })
        assertEquals(listOf(
            "Continue watching Movies", "Continue watching Series",
            "Recently added Movies", "Recently added Series", "Recently added Live TV",
            "Recently played Movies", "Recently played Series", "Recently played Live TV",
            "Favorite Movies", "Favorite Series", "Favorite Live TV"
        ), MyTvivoShelfDefinitions.all.map { it.title })
        assertEquals(listOf(
            MyTvivoShelfKind.ContinueWatching, MyTvivoShelfKind.ContinueWatching,
            MyTvivoShelfKind.RecentlyAdded, MyTvivoShelfKind.RecentlyAdded, MyTvivoShelfKind.RecentlyAdded,
            MyTvivoShelfKind.RecentlyPlayed, MyTvivoShelfKind.RecentlyPlayed, MyTvivoShelfKind.RecentlyPlayed,
            MyTvivoShelfKind.Favorites, MyTvivoShelfKind.Favorites, MyTvivoShelfKind.Favorites
        ), MyTvivoShelfDefinitions.all.map { it.kind })
        assertEquals(ShelfContentType.Live, MyTvivoShelfDefinitions.all.last().type)
    }

    @Test fun weightedReservoirIsBoundedStableAndExcludesRecent() {
        val source = (1..1000).asSequence()
        val first = WeightedSuggestions.sample(source, 20, 42L, { (it % 11).toDouble() }, { it < 10 })
        val second = WeightedSuggestions.sample((1..1000).asSequence(), 20, 42L,
            { (it % 11).toDouble() }, { it < 10 })
        assertEquals(first, second)
        assertEquals(20, first.size)
        assertEquals(20, first.distinct().size)
        assertFalse(first.any { it < 10 })
    }

    @Test fun emptyAndProgressRules() {
        assertTrue(HomeRules.isFirstRun(0, 0))
        assertFalse(HomeRules.isFirstRun(1, 0))
        assertFalse(HomeRules.isFirstRun(0, 1))
        assertEquals(0.5f, HomeRules.progress(50, 100))
        assertEquals(null, HomeRules.progress(50, 0))
        assertFalse(HomeRules.isNew(100, 100_000))
        assertTrue(HomeRules.isNew(101, 100_000))
    }

    @Test fun backReturnsToMyTvivoThenExits() {
        ShellTab.entries.filter { it != ShellTab.MyTvivo }.forEach {
            assertEquals(ShellNavigation.BackAction.ReturnToMyTvivo,
                ShellNavigation.backFromTopLevel(it))
        }
        assertEquals(ShellNavigation.BackAction.ShowExitConfirmation,
            ShellNavigation.backFromTopLevel(ShellTab.MyTvivo))
    }
}
