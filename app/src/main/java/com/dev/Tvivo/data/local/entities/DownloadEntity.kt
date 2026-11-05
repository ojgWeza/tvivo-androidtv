package com.dev.Tvivo.data.local.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "downloads",
    primaryKeys = ["accountId", "kind", "seriesId", "mediaId"],
    indices = [Index(value = ["state"])]
)
data class DownloadEntity(
    val accountId: String,
    val kind: String,
    val seriesId: Int,
    val mediaId: String,
    val containerExtension: String? = null,
    val state: String,
    val expectedBytes: Long? = null,
    val partialBytes: Long = 0,
    val reservedBytes: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    /** App-private relative path only. This must never contain a URL or credentials. */
    val relativePath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val failureReason: String? = null
) {
    init {
        require(kind == "movie" || kind == "episode")
        require(mediaId.isNotBlank())
        require(if (kind == "movie") seriesId == 0 else seriesId > 0)
    }
}
