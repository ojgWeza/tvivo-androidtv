package com.dev.Tvivo.ui.browse

/** Compose lazy keys keep synthetic folders, panel categories, and row chrome in separate spaces. */
internal object CatalogLazyKeys {
    fun landingHeader() = "landing:header"

    fun pinnedShelf(id: String) = "landing:pinned:$id"

    fun allFolder(id: String) = "landing:virtual:$id"

    fun category(id: String) = "landing:category:$id"

    fun categoryPicker(ids: List<String>): List<String> = unique("picker:category", ids)

    fun categoryRows(ids: List<String>): List<String> = unique("landing:category", ids)

    fun pinnedRows(ids: List<String>): List<String> = unique("landing:pinned", ids)

    fun landingRows(pinnedIds: List<String>, allId: String?, categoryIds: List<String>): List<String> =
        buildList {
            add(landingHeader())
            addAll(unique("landing:pinned", pinnedIds))
            if (allId != null) add(allFolder(allId))
            addAll(unique("landing:category", categoryIds))
        }

    private fun unique(namespace: String, ids: List<String>): List<String> {
        val occurrences = mutableMapOf<String, Int>()
        return ids.map { id ->
            val occurrence = occurrences.getOrDefault(id, 0)
            occurrences[id] = occurrence + 1
            "$namespace:$id:$occurrence"
        }
    }
}
