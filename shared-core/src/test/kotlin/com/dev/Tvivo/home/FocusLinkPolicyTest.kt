package com.dev.Tvivo.home

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusLinkPolicyTest {
    @Test fun returnsOnlyTargetsInRenderedSet() {
        val rendered = setOf("sort", "continue")
        assertEquals("continue", FocusLinkPolicy.targetIfRendered("continue", rendered))
        assertEquals(null, FocusLinkPolicy.targetIfRendered("unloaded-category", rendered))
        assertEquals(null, FocusLinkPolicy.targetIfRendered(null, rendered))
    }

    @Test fun conditionalDirectionalTargetsAreOmittedWhenTheirNodesAreAbsent() {
        val gridRequester = Any()
        val railRequester = Any()
        val unavailableLinks = listOf(
            "loading/filter-down" to gridRequester,
            "empty/filter-down" to gridRequester,
            "error/filter-down" to gridRequester,
            "no-match/filter-down" to gridRequester,
            "empty/filter-pill-down" to gridRequester,
            "empty/category-right" to gridRequester,
            "panel-closed/filter-left" to railRequester
        )
        unavailableLinks.forEach { (directionAndState, target) ->
            assertEquals(directionAndState, null,
                FocusLinkPolicy.targetIfRendered(target, false))
        }
        // Every unavailable edge resolves to Compose's default-search path at the callsite.
        assertEquals(gridRequester, FocusLinkPolicy.targetIfRendered(gridRequester, true))
        assertEquals(railRequester, FocusLinkPolicy.targetIfRendered(railRequester, true))
    }

    @Test fun shelfRestoreKeysIncludeTheirOriginShelf() {
        assertEquals("continue|MOVIES:42", ShelfRestoreKey.of("continue", "MOVIES:42"))
        assertEquals("favourites|MOVIES:42", ShelfRestoreKey.of("favourites", "MOVIES:42"))
    }
}
