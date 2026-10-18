package com.dev.Tvivo.ui.browse

import com.dev.Tvivo.data.local.entities.CategoryEntity

/** Entry routes may supply a visible list that already contains its virtual prefix. */
internal enum class CategoryPickerEntryRoute { Landing, SeeAll, SearchSeeAll, MyTvivoSeeAll }

/** Virtual folders lead; duplicate ids and duplicate display titles are removed once here. */
@Suppress("UNUSED_PARAMETER")
internal fun categoryPickerModel(
    virtualCategories: List<CategoryEntity>,
    routeCategories: List<CategoryEntity>,
    route: CategoryPickerEntryRoute
): List<CategoryEntity> {
    val seenIds = mutableSetOf<String>()
    val seenTitles = mutableSetOf<String>()
    return (virtualCategories + routeCategories).filter { category ->
        seenIds.add(category.categoryId) && seenTitles.add(category.name.trim().lowercase())
    }
}
