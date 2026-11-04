package com.dev.Tvivo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dev.Tvivo.data.local.entities.MediaFileSizeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaFileSizeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(row: MediaFileSizeEntity)

    @Query("""UPDATE media_file_sizes SET exactBytes = :bytes, estimatedBytes = NULL
        WHERE accountId = :accountId AND kind = :kind AND seriesId = :seriesId AND mediaId = :mediaId""")
    suspend fun updateExact(accountId: String, kind: String, seriesId: Int, mediaId: String, bytes: Long): Int

    @Query("""UPDATE media_file_sizes SET estimatedBytes = :bytes
        WHERE accountId = :accountId AND kind = :kind AND seriesId = :seriesId AND mediaId = :mediaId""")
    suspend fun updateEstimate(accountId: String, kind: String, seriesId: Int, mediaId: String, bytes: Long): Int

    @Query("""UPDATE media_file_sizes SET lastProbeAt = :at, lastProbeOutcome = :outcome
        WHERE accountId = :accountId AND kind = :kind AND seriesId = :seriesId AND mediaId = :mediaId""")
    suspend fun updateProbe(accountId: String, kind: String, seriesId: Int, mediaId: String, at: Long, outcome: String): Int

    @Transaction
    suspend fun upsertExact(row: MediaFileSizeEntity) {
        require(row.exactBytes != null) { "Exact size must be present" }
        insertIfMissing(row.copy(estimatedBytes = null, lastProbeAt = null, lastProbeOutcome = null))
        updateExact(row.accountId, row.kind, row.seriesId, row.mediaId, row.exactBytes)
    }

    /** Estimates only fill the estimate field, preserving any previously probed exact value. */
    @Transaction
    suspend fun upsertEstimate(row: MediaFileSizeEntity) {
        require(row.estimatedBytes != null) { "Estimated size must be present" }
        insertIfMissing(row.copy(exactBytes = null, lastProbeAt = null, lastProbeOutcome = null))
        updateEstimate(row.accountId, row.kind, row.seriesId, row.mediaId, row.estimatedBytes)
    }

    @Transaction
    suspend fun recordProbeAttempt(accountId: String, kind: String, seriesId: Int, mediaId: String, at: Long, outcome: String) {
        insertIfMissing(MediaFileSizeEntity(accountId, kind, seriesId, mediaId))
        updateProbe(accountId, kind, seriesId, mediaId, at, outcome)
    }

    @Query("""SELECT * FROM media_file_sizes WHERE accountId = :accountId AND kind = :kind
        AND seriesId = :seriesId AND mediaId = :mediaId""")
    suspend fun get(accountId: String, kind: String, seriesId: Int, mediaId: String): MediaFileSizeEntity?

    @Query("""SELECT * FROM media_file_sizes WHERE accountId = :accountId AND kind = :kind
        AND seriesId = :seriesId AND mediaId IN (:mediaIds)""")
    fun observeForMediaIds(accountId: String, kind: String, seriesId: Int, mediaIds: List<String>): Flow<List<MediaFileSizeEntity>>

    @Query("""SELECT * FROM media_file_sizes WHERE accountId = :accountId AND kind = :kind
        AND seriesId = :seriesId AND mediaId IN (:mediaIds)""")
    suspend fun getForMediaIds(accountId: String, kind: String, seriesId: Int, mediaIds: List<String>): List<MediaFileSizeEntity>

    @Query("DELETE FROM media_file_sizes WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: String)
}
