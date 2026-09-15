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
    /**
     * The panel sends `plot` on VOD rows just as it does on series rows, and
     * [StreamListParser] has always read it — the VOD mapping simply dropped it, and
     * this column did not exist to put it in. The detail screen is the first thing that
     * needed it. Nullable because a panel is free not to send it.
     */
    val plot: String?,
    /**
     * Panel rating normalised to 0–10, or null when unrated. See
     * [com.dev.Tvivo.data.remote.StreamListParser.RawStream.rating] — `0` from the panel
     * means *unrated* and is stored as null, so a null check is the only rendering test
     * a caller needs.
     */
    val rating: Double?,
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

/**
 * Live channels get their own table rather than a `type` column on [VodStreamEntity]:
 * `stream_id` is only unique *within* a content type on this panel, so a shared
 * `(accountId, streamId)` key would let a channel and a film overwrite each other.
 *
 * `ext` here is the live equivalent of `container_extension` — a different field name
 * for the same job, normalised at the parser boundary.
 */
@Entity(
    tableName = "live_streams",
    primaryKeys = ["accountId", "streamId"],
    indices = [
        Index(value = ["accountId", "categoryId", "nameNormalized"]),
        Index(value = ["accountId", "nameNormalized"])
    ]
)
data class LiveStreamEntity(
    val accountId: String,
    val streamId: Int,
    val categoryId: String?,
    val name: String,
    val nameDisplay: String,
    val nameNormalized: String,
    val streamIcon: String?,
    val ext: String?,
    val added: Long?,
    /** Panel channel number. Kept for diagnostics; grids sort alphabetically
     *  (`docs/decisions.md`, "Category grids sort alphabetically"). */
    val num: Int?,
    val generation: Long
)

/**
 * A series *show* — the middle layer that live and movies do not have. It is not
 * playable: `series_id` addresses nothing on the stream endpoints, only
 * `get_series_info`, and only episodes carry a playable id.
 *
 * Its own table for the same reason live has one: `series_id` collides with the
 * `stream_id` space of both other content types.
 *
 * The panel names two fields differently here — `cover` for the poster and
 * `last_modified` for the timestamp — and both are normalised at the parser boundary
 * onto [streamIcon] / [added] so the browse grid stays type-agnostic.
 */
@Entity(
    tableName = "series",
    primaryKeys = ["accountId", "seriesId"],
    indices = [
        Index(value = ["accountId", "categoryId", "nameNormalized"]),
        Index(value = ["accountId", "nameNormalized"]),
        Index(value = ["accountId", "added"])
    ]
)
data class SeriesEntity(
    val accountId: String,
    val seriesId: Int,
    val categoryId: String?,
    val name: String,
    val nameDisplay: String,
    val nameNormalized: String,
    val streamIcon: String?,
    val plot: String?,
    /** Normalised to 0–10, null when unrated. Same contract as `vod_streams.rating`. */
    val rating: Double?,
    val added: Long?,
    val num: Int?,
    val generation: Long
)

/**
 * One episode of one show, cached from `get_series_info`.
 *
 * [episodeId] is a **string**: the panel returns it quoted, it is the id that goes in
 * the playback URL, and nothing is gained by round-tripping it through Int.
 *
 * [seasonNumber] comes from the key of the `episodes` object, which is the season number
 * as a string. The episode body repeats it in `season`, but the key is authoritative —
 * a handful of shows disagree between the two.
 */
@Entity(
    tableName = "series_episodes",
    primaryKeys = ["accountId", "seriesId", "episodeId"],
    indices = [Index(value = ["accountId", "seriesId", "seasonNumber", "episodeNum"])]
)
data class EpisodeEntity(
    val accountId: String,
    val seriesId: Int,
    val episodeId: String,
    val seasonNumber: Int,
    val episodeNum: Int,
    val title: String,
    val containerExtension: String?,
    val durationSecs: Int?,
    val added: Long?
)
