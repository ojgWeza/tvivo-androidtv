package com.dev.Tvivo.ui.browse

import com.dev.Tvivo.data.local.entities.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryPickerModelTest {
    @Test
    fun everyPickerEntryRouteContainsEachVirtualFolderAndTitleOnce() {
        val virtual = listOf(
            category("__all", "ALL"), category("__recent", "RECENTLY ADDED"),
            category("__continue", "CONTINUE WATCHING"), category("__favourites", "FAVOURITES")
        )
        // BrowseUiState.visibleCategories already includes the virtual prefix. Landing
        // and destination routes that supply only panel categories are safe too.
        val routes = listOf(
            CategoryPickerEntryRoute.Landing to (virtual + category("1", "Movies")),
            CategoryPickerEntryRoute.SeeAll to (virtual + category("1", "Movies")),
            CategoryPickerEntryRoute.SearchSeeAll to listOf(category("1", "Movies")),
            CategoryPickerEntryRoute.MyTvivoSeeAll to listOf(category("1", "Movies"))
        )

        routes.forEach { (route, supplied) ->
            val model = categoryPickerModel(virtual, supplied, route)
            assertEquals("duplicate ids for $route", model.size, model.map { it.categoryId }.toSet().size)
            assertEquals("duplicate titles for $route", model.size, model.map { it.name.lowercase() }.toSet().size)
            assertEquals(listOf("__all", "__recent", "__continue", "__favourites", "1"),
                model.map { it.categoryId })
        }
    }

    private fun category(id: String, title: String) =
        CategoryEntity("account", "vod", id, title, 0)
}
