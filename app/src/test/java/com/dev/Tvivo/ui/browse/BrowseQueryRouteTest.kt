package com.dev.Tvivo.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrowseQueryRouteTest {
    @Test fun allUsesPagingForBothUnfilteredAndFilteredPaths() {
        assertEquals(BrowseQueryRoute.ALL, browseQueryRoute(VirtualFolder.ALL.id, ""))
        assertEquals(BrowseQueryRoute.ALL_FILTERED, browseQueryRoute(VirtualFolder.ALL.id, "movie"))
    }

    @Test fun boundedVirtualFoldersStaySeparateFromAll() {
        assertEquals(BrowseQueryRoute.VIRTUAL,
            browseQueryRoute(VirtualFolder.RECENTLY_ADDED.id, "movie"))
        assertEquals(BrowseQueryRoute.CATEGORY_FILTERED, browseQueryRoute("123", "movie"))
    }

    @Test fun allLeadsTheRailWhileRecentlyAddedRemainsTheOpeningFolder() {
        assertEquals(VirtualFolder.ALL, VirtualFolder.forContentType(false).first())
        assertEquals(VirtualFolder.RECENTLY_ADDED, VirtualFolder.forContentType(false)[1])
        assertEquals(VirtualFolder.RECENTLY_PLAYED,
            VirtualFolder.forContentType(true)[2])
    }

    @Test fun sortPreferenceIsScopedToAccountProfileAndContentType() {
        val key = browseSortStateKey("account", "profile-a", "vod")
        assertEquals("catalogSort:account:profile-a:vod", key)
        assertNotEquals(key, browseSortStateKey("other", "profile-a", "vod"))
        assertNotEquals(key, browseSortStateKey("account", "profile-b", "vod"))
        assertNotEquals(key, browseSortStateKey("account", "profile-a", "live"))
    }
}
