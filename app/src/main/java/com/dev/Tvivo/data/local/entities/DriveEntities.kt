package com.dev.Tvivo.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

const val TYPE_MUSIC = "music"

@Entity(tableName = "drive_movies", primaryKeys = ["accountId", "id", "generation"], indices = [
    Index(value = ["accountId", "categoryId", "sortOrder"]),
    Index(value = ["accountId", "nameNormalized"]), Index(value = ["accountId", "generation"])
])
data class DriveMovieEntity(
    val accountId: String, val id: String, val categoryId: String,
    val title: String, val nameDisplay: String, val nameNormalized: String,
    val directUrl: String, val posterUrl: String?, val year: Int?, val plot: String?,
    val sortOrder: Int, val generation: Long
)

@Entity(tableName = "drive_shows", primaryKeys = ["accountId", "id", "generation"], indices = [
    Index(value = ["accountId", "categoryId", "sortOrder"]),
    Index(value = ["accountId", "nameNormalized"]), Index(value = ["accountId", "generation"])
])
data class DriveShowEntity(
    val accountId: String, val id: String, val categoryId: String,
    val title: String, val nameDisplay: String, val nameNormalized: String,
    val posterUrl: String?, val year: Int?, val plot: String?,
    val sortOrder: Int, val generation: Long
)

@Entity(tableName = "drive_seasons", primaryKeys = ["accountId", "showId", "seasonNumber", "generation"],
    foreignKeys = [ForeignKey(entity = DriveShowEntity::class,
        parentColumns = ["accountId", "id", "generation"], childColumns = ["accountId", "showId", "generation"],
        onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "showId", "sortOrder"]),
        Index(value = ["accountId", "showId", "generation"])])
data class DriveSeasonEntity(
    val accountId: String, val showId: String, val seasonNumber: Int,
    val title: String?, val sortOrder: Int, val generation: Long
)

@Entity(tableName = "drive_episodes", primaryKeys = ["accountId", "id", "generation"],
    foreignKeys = [ForeignKey(entity = DriveSeasonEntity::class,
        parentColumns = ["accountId", "showId", "seasonNumber", "generation"],
        childColumns = ["accountId", "showId", "seasonNumber", "generation"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "showId", "seasonNumber", "sortOrder"]),
        Index(value = ["accountId", "generation"]),
        Index(value = ["accountId", "showId", "seasonNumber", "generation"])])
data class DriveEpisodeEntity(
    val accountId: String, val id: String, val showId: String,
    val seasonNumber: Int, val episodeNumber: Int,
    val title: String, val nameDisplay: String, val nameNormalized: String,
    val directUrl: String, val posterUrl: String?, val plot: String?,
    val sortOrder: Int, val generation: Long
)

@Entity(tableName = "music_tracks", primaryKeys = ["accountId", "id", "generation"], indices = [
    Index(value = ["accountId", "categoryId", "sortOrder"]),
    Index(value = ["accountId", "nameNormalized"]), Index(value = ["accountId", "generation"])
])
data class MusicTrackEntity(
    val accountId: String, val id: String, val categoryId: String,
    val title: String, val nameDisplay: String, val nameNormalized: String,
    val directUrl: String, val artist: String?, val album: String?, val artworkUrl: String?,
    val year: Int?, val durationSeconds: Int?, val sortOrder: Int, val generation: Long
)

@Entity(tableName = "drive_index_state", primaryKeys = ["accountId", "kind"],
    indices = [Index(value = ["accountId", "bootstrapState"])])
data class DriveIndexStateEntity(
    val accountId: String, val kind: String, val folderId: String? = null,
    val fileId: String? = null, val version: String? = null, val md5Checksum: String? = null,
    val modifiedAtMillis: Long? = null, val sizeBytes: Long? = null,
    val generation: Long = 0, val lastSuccessfulSyncAt: Long? = null,
    val bootstrapState: String = "none"
)
