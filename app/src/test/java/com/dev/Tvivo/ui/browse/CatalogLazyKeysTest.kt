package com.dev.Tvivo.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLazyKeysTest {
    @Test
    fun landingAndPickerKeysAreUniqueAcrossVirtualAndPanelIds() {
        val all = "__all"
        val recent = "__recently_added"
        val continueWatching = "__continue_watching"
        val favourites = "__favourites"
        val keys = CatalogLazyKeys.landingRows(
            pinnedIds = listOf(recent, continueWatching, favourites),
            allId = all,
            categoryIds = listOf("movies", "__all")
        )
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("landing:header", keys.first())
        assertTrue(keys.any { it == "landing:virtual:__all" })
        assertTrue(keys.any { it.startsWith("landing:category:__all:") })
        assertTrue(keys.any { it.startsWith("landing:pinned:__recently_added:") })

        val pickerKeys = CatalogLazyKeys.categoryPicker(
            listOf(all, recent, continueWatching, favourites, "movies", all)
        )
        assertEquals(pickerKeys.size, pickerKeys.toSet().size)
        assertTrue(pickerKeys.any { it.startsWith("picker:category:__all:") })
    }
}
