package com.dev.Tvivo.data.local.entities

import androidx.room.Entity

/** Account scoped size metadata, independent of replaceable catalog and episode rows. */
@Entity(
    tableName = "media_file_sizes",
    primaryKeys = ["accountId", "kind", "seriesId", "mediaId"]
)
data class MediaFileSizeEntity(
    val accountId: String,
    /** `movie` or `episode`; no size records are stored for series or live streams. */
    val kind: String,
    /** Zero for movies; the owning series id for episodes. */
    val seriesId: Int,
    /** Movie stream id or episode id (episode IDs remain strings). */
    val mediaId: String,
    val exactBytes: Long? = null,
    val estimatedBytes: Long? = null,
    val lastProbeAt: Long? = null,
    val lastProbeOutcome: String? = null
)
