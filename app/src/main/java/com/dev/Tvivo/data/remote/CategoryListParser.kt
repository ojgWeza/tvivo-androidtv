package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.google.gson.JsonParser

/**
 * `get_{live,vod,series}_categories` → [CategoryEntity]. All three return the same
 * shape, so the content type is a parameter rather than three copies of this.
 *
 * Category lists are small (~120 items), so unlike the stream lists these are read
 * whole rather than streamed.
 */
object CategoryListParser {

    fun parse(json: String, accountId: String, type: String): List<CategoryEntity> {
        if (json.isBlank()) return emptyList()
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray.mapIndexedNotNull { index, element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@mapIndexedNotNull null
            // `category_id` is a string on VOD/live and an int on series objects —
            // normalise on insert or grouped counts silently miss rows.
            val id = obj.get("category_id")?.takeIf { !it.isJsonNull }?.asString
                ?: return@mapIndexedNotNull null
            val name = obj.get("category_name")?.takeIf { !it.isJsonNull }?.asString ?: id
            CategoryEntity(
                accountId = accountId,
                type = type,
                categoryId = id,
                name = name,
                ordering = index
            )
        }
    }
}
