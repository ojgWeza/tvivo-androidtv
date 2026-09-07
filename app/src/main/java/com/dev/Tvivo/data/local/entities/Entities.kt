package com.dev.Tvivo.data.local.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Every cached row is account-scoped. `item_id` alone collides across accounts, so
 * every key here is composite. Pointing the app at a different panel must never
 * surface the previous account's catalog, artwork or resume positions.
 */

const val TYPE_LIVE = "live"
const val TYPE_VOD = "vod"
const val TYPE_SERIES = "series"

/** The category list itself is cached under this sentinel, on the same TTL. */
const val CATEGORY_LIST_SENTINEL = "__categories__"

@Entity(
    tableName = "categories",
    primaryKeys = ["accountId", "type", "categoryId"]
)
data class CategoryEntity(
    val accountId: String,
    val type: String,
    val categoryId: String,
    val name: String,
    /** Panel order, kept so the rail can render categories as the panel lists them. */
    val ordering: Int
)

/**
 * [generation] exists so a full-catalog sync can write ~48,751 rows in chunked
 * transactions and then flip to the new generation in one small final transaction,
 * instead of holding a multi-second write lock that freezes every read.
 */
@Entity(
    tableName = "vod_streams",
    primaryKeys = ["accountId", "streamId"],
    indices = [
        Index(value = ["accountId", "categoryId", "nameNormalized"]),
        Index(value = ["accountId", "nameNormalized"]),
        Index(value = ["accountId", "added"])
    ]
)
data class VodStreamEntity(
    val accountId: String,
    val streamId: Int,
    val categoryId: String?,
    /** Exactly as the panel returned it. Diagnostics only, never rendered. */
    val name: String,
    /** Quality tokens stripped, whitespace collapsed, case and diacritics preserved. */
    val nameDisplay: String,
    /** Additionally lowercased and diacritics-stripped. Matching and sorting only. */
    val nameNormalized: String,
    val streamIcon: String?,
    val containerExtension: String?,
    val added: Long?,
    val num: Int?,
    val generation: Long
)

@Entity(
    tableName = "sync_meta",
    primaryKeys = ["accountId", "contentType", "categoryId"]
)
data class SyncMetaEntity(
    val accountId: String,
    val contentType: String,
    val categoryId: String,
    val lastSyncedAt: Long,
    val generation: Long
)

@Entity(
    tableName = "catalog_sync",
    primaryKeys = ["accountId", "contentType"]
)
data class CatalogSyncEntity(
    val accountId: String,
    val contentType: String,
    /** not_started | indexing | complete | failed | stale */
    val state: String,
    val done: Int,
    val total: Int,
    val updatedAt: Long
)

@Entity(
    tableName = "resume_positions",
    primaryKeys = ["accountId", "contentType", "itemId"]
)
data class ResumePositionEntity(
    val accountId: String,
    val contentType: String,
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "favourites",
    primaryKeys = ["accountId", "contentType", "itemId"]
)
data class FavouriteEntity(
    val accountId: String,
    val contentType: String,
    val itemId: String,
    val addedAt: Long
)
